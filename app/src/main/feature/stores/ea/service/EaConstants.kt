package com.winlator.cmod.feature.stores.ea.service

object EaConstants {
    const val EA_INTERACTIVE_CLIENT_ID = "EADOTCOM-WEB-SERVER"
    const val EA_TOKEN_CLIENT_ID = "ORIGIN_JS_SDK"
    const val EA_AUTH_URL = "https://accounts.ea.com/connect/auth"
    const val EA_INTERACTIVE_REDIRECT = "https://www.ea.com/login"
    const val EA_ACCOUNTS_ORIGIN = "https://accounts.ea.com"
    const val EA_USER_AGENT = "EADesktop/13.783.0.6296"

    const val EA_CONNECTED_ACCOUNTS_URL =
        "https://myaccount.ea.com/am/ui/connected-accounts?locale=en_US"

    const val EA_GRAPHQL_URL = "https://service-aggregation-layer.juno.ea.com/graphql"
    const val EA_REMOTE_CONFIG_URL = "https://desktop-config.juno.ea.com/globalConfig.json"

    fun interactiveLoginUrl(): String =
        EA_AUTH_URL +
            "?client_id=" + EA_INTERACTIVE_CLIENT_ID +
            "&response_type=code" +
            "&redirect_uri=https%3A%2F%2Fwww.ea.com%2Flogin" +
            "&locale=en_US"

    fun silentTokenUrl(): String =
        EA_AUTH_URL +
            "?client_id=" + EA_TOKEN_CLIENT_ID +
            "&response_type=token" +
            "&redirect_uri=nucleus%3Arest" +
            "&prompt=none" +
            "&release_type=prod"

    fun isInteractiveRedirect(url: String): Boolean =
        url.startsWith("https://www.ea.com/") && !url.startsWith(EA_AUTH_URL)

    fun isTokenResponseUrl(url: String): Boolean = url.startsWith(EA_AUTH_URL)
}
