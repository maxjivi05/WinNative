use flate2::bufread::ZlibDecoder;
use std::io::Read;

fn chunk(bytes: &[u8], limit: usize) -> Result<Vec<u8>, &'static str> {
    let (&mode, data) = bytes.split_first().ok_or("invalid_blte")?;
    match mode {
        b'N' if data.len() <= limit => Ok(data.to_vec()),
        b'Z' => {
            let mut decoder = ZlibDecoder::new(data);
            let mut output = Vec::new();
            decoder
                .by_ref()
                .take(limit as u64 + 1)
                .read_to_end(&mut output)
                .map_err(|_| "invalid_compression")?;
            if output.len() > limit || decoder.total_in() != data.len() as u64 {
                return Err("invalid_compression");
            }
            Ok(output)
        }
        b'E' => Err("encryption_key_required"),
        _ => Err("unsupported_blte"),
    }
}

pub fn decode(bytes: &[u8], limit: usize) -> Result<Vec<u8>, &'static str> {
    if bytes.get(..4) != Some(b"BLTE") || bytes.len() < 9 || limit > 512 * 1024 * 1024 {
        return Err("invalid_blte");
    }
    let header = u32::from_be_bytes(bytes[4..8].try_into().unwrap()) as usize;
    if header == 0 {
        return chunk(&bytes[8..], limit);
    }
    if header < 12 || header > bytes.len() || bytes[8] != 0x0f {
        return Err("invalid_blte");
    }
    let count = u32::from_be_bytes([0, bytes[9], bytes[10], bytes[11]]) as usize;
    if count == 0 || 12 + count * 24 != header {
        return Err("invalid_blte");
    }
    let mut output = Vec::new();
    let mut offset = header;
    for entry in bytes[12..header].as_chunks::<24>().0 {
        let encoded = u32::from_be_bytes(entry[..4].try_into().unwrap()) as usize;
        let decoded = u32::from_be_bytes(entry[4..8].try_into().unwrap()) as usize;
        if decoded > limit - output.len() || encoded == 0 {
            return Err("invalid_blte");
        }
        let end = offset.checked_add(encoded).ok_or("invalid_blte")?;
        let data = bytes.get(offset..end).ok_or("invalid_blte")?;
        if md5::compute(data).as_ref() != &entry[8..24] {
            return Err("checksum_mismatch");
        }
        let decoded_data = chunk(data, decoded)?;
        if decoded_data.len() != decoded {
            return Err("invalid_blte");
        }
        output.extend_from_slice(&decoded_data);
        offset = end;
    }
    if offset != bytes.len() {
        return Err("invalid_blte");
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    fn frame(data: &[u8], decoded: u32) -> Vec<u8> {
        let mut bytes = b"BLTE\0\0\0\x24\x0f\0\0\x01".to_vec();
        bytes.extend_from_slice(&(data.len() as u32).to_be_bytes());
        bytes.extend_from_slice(&decoded.to_be_bytes());
        bytes.extend_from_slice(md5::compute(data).as_ref());
        bytes.extend_from_slice(data);
        bytes
    }
    #[test]
    fn raw_and_framed() {
        assert_eq!(decode(b"BLTE\0\0\0\0Nhello", 5).unwrap(), b"hello");
        assert_eq!(decode(&frame(b"Nhello", 5), 5).unwrap(), b"hello");
    }
    #[test]
    fn compressed_and_bounded() {
        let mut encoder =
            flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(&[b'a'; 1024]).unwrap();
        let mut data = vec![b'Z'];
        data.extend(encoder.finish().unwrap());
        let bytes = frame(&data, 1024);
        assert_eq!(decode(&bytes, 1024).unwrap(), vec![b'a'; 1024]);
        assert!(decode(&bytes, 1023).is_err());
        assert!(decode(&frame(&data, 16), 1024).is_err());
    }
    #[test]
    fn rejects_corruption_and_trailing_bytes() {
        let mut bytes = frame(b'N'.to_be_bytes().as_ref(), 0);
        bytes[20] ^= 1;
        assert_eq!(decode(&bytes, 100), Err("checksum_mismatch"));
        let mut bytes = frame(b"Nhello", 5);
        bytes.push(0);
        assert!(decode(&bytes, 100).is_err());
    }
    #[test]
    fn rejects_every_truncated_prefix() {
        let bytes = frame(b"Nhello", 5);
        for length in 0..bytes.len() {
            assert!(decode(&bytes[..length], 100).is_err());
        }
    }
    #[test]
    fn encrypted_chunks_fail_explicitly() {
        assert_eq!(
            decode(b"BLTE\0\0\0\0Esecret", 100),
            Err("encryption_key_required")
        );
    }
}
