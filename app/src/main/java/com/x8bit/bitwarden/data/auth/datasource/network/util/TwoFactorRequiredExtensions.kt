package com.x8bit.bitwarden.data.auth.datasource.network.util

import com.x8bit.bitwarden.data.auth.datasource.network.model.GetTokenResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.TwoFactorAuthMethod

/**
 * Return the list of two-factor auth methods available to the user.
 */
val GetTokenResponseJson.TwoFactorRequired?.availableAuthMethods: List<TwoFactorAuthMethod>
    get() = (
        this
            ?.authMethodsData
            ?.keys
            ?.toList()
            ?: listOf(TwoFactorAuthMethod.EMAIL)
        )
        .plus(TwoFactorAuthMethod.RECOVERY_CODE)

/**
 * The preferred two-factor auth method to be used as a default on the two-factor login screen.
 */
val GetTokenResponseJson.TwoFactorRequired?.preferredAuthMethod: TwoFactorAuthMethod
    get() = this
        ?.authMethodsData
        ?.keys
        ?.maxByOrNull { it.priority }
        ?: TwoFactorAuthMethod.EMAIL

/**
 * If it exists, return the value to display for the email used with two-factor authentication.
 */
val GetTokenResponseJson.TwoFactorRequired?.twoFactorDisplayEmail: String
    get() = this
        ?.authMethodsData
        ?.get(TwoFactorAuthMethod.EMAIL)
        ?.get("Email")
        ?: ""