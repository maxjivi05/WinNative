// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "lsfg_pacer.hpp"

#include <algorithm>
#include <cmath>
#include <utility>

namespace lsfg {

namespace {

using Clock = std::chrono::steady_clock;

constexpr float INTERVAL_SMOOTHING = 0.25f;
constexpr float SOURCE_SMOOTHING = 0.15f;
constexpr float SOURCE_STALE_SECONDS = 0.5f;
constexpr float DISCONTINUITY_SECONDS = 0.25f;
constexpr float SLOT_EPSILON = 0.02f;
constexpr float CREDIT_EPSILON = 1.0e-4f;
constexpr float SOURCE_ACCUM_FLOOR = 0.01f;
constexpr uint32_t MIN_RATE_SAMPLES = 12;
constexpr float RATE_JUMP_HIGH = 1.4f;
constexpr float RATE_JUMP_LOW = 0.7f;
constexpr uint32_t RATE_JUMP_SAMPLES = 3;

constexpr float PROBE_THROUGHPUT_TOLERANCE = 0.95f;
constexpr float PROBE_BASE_COLLAPSE_RATIO = 0.70f;
constexpr float PROBE_MARGINAL_GAIN = 1.15f;
constexpr float TARGET_SATISFIED_RATIO = 0.95f;
constexpr float UNLOADED_BASE_RETENTION = 0.75f;
constexpr uint32_t MAX_PROBE_FAILURES = 4;
constexpr float INTERVAL_SPIKE_CLAMP = 2.0f;
constexpr float MEASURED_SLOT_TOLERANCE = 0.9f;

constexpr auto STABILIZATION_DURATION = std::chrono::seconds(1);
constexpr auto PROBE_DURATION = std::chrono::seconds(1);
constexpr auto DEFICIT_DURATION = std::chrono::seconds(1);
constexpr auto PROBE_STEP_DELAY = std::chrono::milliseconds(250);
constexpr auto RETENTION_DURATION = std::chrono::milliseconds(500);

[[nodiscard]] Clock::duration ProbeBackoff(uint32_t failures) {
    switch (failures) {
    case 1:
        return std::chrono::seconds(5);
    case 2:
        return std::chrono::seconds(15);
    case 3:
        return std::chrono::seconds(30);
    default:
        return std::chrono::seconds(10);
    }
}

}

size_t LsfgPacer::MaxGenerations() const {
    if (config.multiplier < 2) return 0;
    if (config.target_rate != 0) return LSFG_MAX_MULTIPLIER - 1;
    return std::min<size_t>(config.multiplier, LSFG_MAX_MULTIPLIER) - 1;
}

void LsfgPacer::TrackSourceRate(Clock::time_point now, uint64_t source_frames) {
    if (!last_source_sample) {
        last_source_sample = now;
        last_source_frames = source_frames;
        return;
    }

    const float elapsed = std::chrono::duration<float>(now - *last_source_sample).count();
    if (elapsed <= 0.0f) {
        return;
    }

    last_source_sample = now;
    const uint64_t drawn =
        source_frames > last_source_frames ? source_frames - last_source_frames : 0;
    last_source_frames = source_frames;

    if (elapsed > SOURCE_STALE_SECONDS) {
        source_frame_accum = 0.0f;
        source_time_accum = 0.0f;
        source_interval = 0.0f;
        source_samples = 0;
        return;
    }

    last_drawn = drawn;
    last_elapsed = elapsed;

    const float instant = drawn > 0 ? elapsed / static_cast<float>(drawn) : 0.0f;
    if (instant > 0.0f && source_interval > 0.0f &&
        (instant > source_interval * RATE_JUMP_HIGH || instant < source_interval * RATE_JUMP_LOW)) {
        if (++rate_jumps >= RATE_JUMP_SAMPLES) {
            source_frame_accum = static_cast<float>(drawn);
            source_time_accum = elapsed;
            source_interval = instant;
            source_samples = MIN_RATE_SAMPLES;
            rate_jumps = 0;
            return;
        }
    } else {
        rate_jumps = 0;
    }

    source_frame_accum += (static_cast<float>(drawn) - source_frame_accum) * SOURCE_SMOOTHING;
    source_time_accum += (elapsed - source_time_accum) * SOURCE_SMOOTHING;
    source_interval =
        source_frame_accum > SOURCE_ACCUM_FLOOR ? source_time_accum / source_frame_accum : 0.0f;
    if (source_samples < MIN_RATE_SAMPLES) ++source_samples;
}

void LsfgPacer::TrackLoopRate(float interval_seconds) {
    if (loop_interval > 0.0f && interval_seconds > loop_interval * INTERVAL_SPIKE_CLAMP) {
        interval_seconds = loop_interval * INTERVAL_SPIKE_CLAMP;
    }
    loop_interval = loop_interval > 0.0f
                        ? loop_interval + (interval_seconds - loop_interval) * INTERVAL_SMOOTHING
                        : interval_seconds;
    if (loop_samples < MIN_RATE_SAMPLES) ++loop_samples;
}

void LsfgPacer::TrackUnloadedRate(float interval) {
    if (interval <= 0.0f || previous_generations != 0 || limit != 0) return;
    const float measured = 1.0f / interval;
    unloaded_base_rate =
        unloaded_base_rate > 0.0f
            ? unloaded_base_rate + (measured - unloaded_base_rate) * SOURCE_SMOOTHING
            : measured;
}

bool LsfgPacer::RatesSettled() const {
    return source_samples >= MIN_RATE_SAMPLES && loop_samples >= MIN_RATE_SAMPLES;
}

size_t LsfgPacer::SlotLimit() const {
    if (config.source_rate <= 0.0f || config.refresh_rate <= 0.0f) {
        return LSFG_MAX_MULTIPLIER - 1;
    }

    const float slots = std::floor(config.refresh_rate / config.source_rate + SLOT_EPSILON);
    return slots < 2.0f ? 0 : static_cast<size_t>(slots) - 1;
}

void LsfgPacer::Stabilize(Clock::time_point now) {
    stable_until = now + STABILIZATION_DURATION;
    probe_until.reset();
    deficit_since.reset();
    retention_since.reset();
    loop_interval = 0.0f;
    loop_samples = 0;
    output_credit = 0.0f;
}

void LsfgPacer::UpdateLimit(Clock::time_point now, float base_rate, float target_rate,
                            size_t ceiling) {
    limit = std::min(limit, ceiling);

    if (unloaded_base_rate > 0.0f && base_rate > unloaded_base_rate) {
        unloaded_base_rate += (base_rate - unloaded_base_rate) * SOURCE_SMOOTHING;
    }

    const bool below_retention = limit > 0 && !probe_until && unloaded_base_rate > 0.0f &&
                                 base_rate < unloaded_base_rate * UNLOADED_BASE_RETENTION;
    if (!below_retention) {
        retention_since.reset();
    } else if (!retention_since) {
        retention_since = now;
    }

    if (below_retention && now - *retention_since >= RETENTION_DURATION) {
        retention_since.reset();
        --limit;
        probe_failures = std::min(probe_failures + 1, MAX_PROBE_FAILURES);
        next_probe = now + ProbeBackoff(probe_failures);
        deficit_since.reset();
        output_credit = 0.0f;
        return;
    }

    if (probe_until) {
        if (now < *probe_until) return;
        probe_until.reset();
        output_credit = 0.0f;

        const float previous_output = std::min(
            target_rate, probe_base_rate * static_cast<float>(probe_previous_limit + 1));
        const float current_output =
            std::min(target_rate, base_rate * static_cast<float>(limit + 1));

        const bool throughput_regressed =
            current_output < previous_output * PROBE_THROUGHPUT_TOLERANCE;
        const bool collapsed_for_marginal_gain =
            base_rate < probe_base_rate * PROBE_BASE_COLLAPSE_RATIO &&
            current_output < previous_output * PROBE_MARGINAL_GAIN;
        const bool source_slowed = unloaded_base_rate > 0.0f &&
                                   base_rate < unloaded_base_rate * UNLOADED_BASE_RETENTION;

        if (throughput_regressed || collapsed_for_marginal_gain || source_slowed) {
            limit = probe_previous_limit;
            probe_failures = std::min(probe_failures + 1, MAX_PROBE_FAILURES);
            next_probe = now + ProbeBackoff(probe_failures);
            deficit_since.reset();
            return;
        }

        probe_failures = 0;
        next_probe = now + PROBE_STEP_DELAY;
    }

    if (base_rate * static_cast<float>(limit + 1) >= target_rate * TARGET_SATISFIED_RATIO ||
        limit >= ceiling) {
        deficit_since.reset();
        return;
    }

    if (!deficit_since) {
        deficit_since = now;
        return;
    }
    if (now - *deficit_since < DEFICIT_DURATION) return;
    if (next_probe && now < *next_probe) return;

    probe_previous_limit = limit;
    probe_base_rate = base_rate;
    ++limit;
    probe_until = now + PROBE_DURATION;
    deficit_since.reset();
    output_credit = 0.0f;
}

LsfgPlan LsfgPacer::Plan(size_t capacity, uint64_t source_frames) {
    const size_t ceiling = std::min(std::min(capacity, MaxGenerations()), SlotLimit());
    if (ceiling == 0) {
        Reset();
        return {};
    }

    const Clock::time_point now = Clock::now();
    TrackSourceRate(now, source_frames);
    previous_generations = std::exchange(issued_generations, 0);
    if (!last_frame) {
        last_frame = now;
        return {};
    }

    const float interval_seconds = std::chrono::duration<float>(now - *last_frame).count();
    last_frame = now;

    if (interval_seconds <= 0.0f || interval_seconds > DISCONTINUITY_SECONDS) {
        Stabilize(now);
        return {};
    }

    TrackLoopRate(interval_seconds);
    TrackUnloadedRate(loop_interval);

    if (stable_until) {
        if (now < *stable_until) return {};
        stable_until.reset();
    }

    float target_rate = static_cast<float>(config.target_rate);
    if (target_rate > 0.0f && config.refresh_rate > 0.0f) {
        target_rate = std::min(target_rate, config.refresh_rate);
    }

    if (target_rate == 0.0f) {
        limit = ceiling;
        output_credit = 0.0f;
        issued_generations = limit;
        return LsfgPlan{limit, limit > 0};
    }

    if (loop_interval <= 0.0f) {
        output_credit = 0.0f;
        return {};
    }

    size_t measured_ceiling = ceiling;
    if (config.source_rate <= 0.0f && config.refresh_rate > 0.0f && unloaded_base_rate > 0.0f) {
        const float slots =
            std::floor(config.refresh_rate / (unloaded_base_rate * MEASURED_SLOT_TOLERANCE));
        measured_ceiling =
            std::min(measured_ceiling, slots < 2.0f ? size_t{0} : static_cast<size_t>(slots) - 1);
    }

    UpdateLimit(now, 1.0f / loop_interval, target_rate, measured_ceiling);

    const size_t allowed = std::min(limit, measured_ceiling);
    const float desired_outputs = loop_interval * target_rate;
    if (allowed == 0 || desired_outputs <= 1.0f) {
        output_credit = 0.0f;
        return {};
    }

    output_credit += desired_outputs;
    const size_t outputs =
        std::max<size_t>(1, static_cast<size_t>(std::floor(output_credit + CREDIT_EPSILON)));
    const size_t generations = std::min(outputs - 1, allowed);

    output_credit -= static_cast<float>(generations + 1);
    if (output_credit < 0.0f) {
        output_credit = 0.0f;
    } else if (generations == allowed && output_credit >= 1.0f) {
        output_credit = std::fmod(output_credit, 1.0f);
    }

    issued_generations = generations;
    return LsfgPlan{generations, generations > 0};
}

LsfgPacerStats LsfgPacer::Stats() const {
    LsfgPacerStats stats;
    stats.source_rate = source_interval > 0.0f ? 1.0f / source_interval : 0.0f;
    stats.loop_rate = loop_interval > 0.0f ? 1.0f / loop_interval : 0.0f;
    stats.refresh_rate = config.refresh_rate;
    stats.target_rate = static_cast<float>(config.target_rate);
    stats.slots = config.refresh_rate * source_interval;
    stats.limit = limit;
    stats.cost_limit = limit;
    stats.rates_settled = RatesSettled();
    stats.last_drawn = last_drawn;
    stats.last_elapsed = last_elapsed;
    stats.source_frames = last_source_frames;
    return stats;
}

void LsfgPacer::Reset() {
    last_frame.reset();
    last_source_sample.reset();
    stable_until.reset();
    probe_until.reset();
    next_probe.reset();
    deficit_since.reset();
    retention_since.reset();
    last_source_frames = 0;
    source_interval = 0.0f;
    source_frame_accum = 0.0f;
    source_time_accum = 0.0f;
    loop_interval = 0.0f;
    source_samples = 0;
    loop_samples = 0;
    last_drawn = 0;
    last_elapsed = 0.0f;
    rate_jumps = 0;
    output_credit = 0.0f;
    unloaded_base_rate = 0.0f;
    probe_base_rate = 0.0f;
    issued_generations = 0;
    previous_generations = 0;
    probe_previous_limit = 0;
    limit = 0;
    probe_failures = 0;
}

}
