package com.museum.reader.cloud

enum class CloudFailureKind { AUTHENTICATION, QUOTA, SERVICE }

/** Public guidance is built from known categories and numeric codes, never response bodies or URLs. */
class CloudServiceException(val provider: CloudProvider, val kind: CloudFailureKind, code: Int? = null) : IllegalStateException(
    provider.label + when (kind) {
        CloudFailureKind.AUTHENTICATION -> "鉴权失败，请检查账号、授权和音色设置"
        CloudFailureKind.QUOTA -> "额度不足或请求过于频繁，请检查服务额度后重试"
        CloudFailureKind.SERVICE -> "服务请求失败，请检查音色配置或稍后重试"
    } + (code?.let { "（错误码 $it）" } ?: "")
) {
    companion object {
        fun http(provider: CloudProvider, status: Int) = CloudServiceException(provider, when (status) {
            401, 403 -> CloudFailureKind.AUTHENTICATION
            429 -> CloudFailureKind.QUOTA
            else -> CloudFailureKind.SERVICE
        }, status)
    }
}

data class CloudProviderFailure(val provider: CloudProvider, val error: Throwable)

/** Retains each cause for safe UI classification while preserving the original diagnostic message. */
class CloudSynthesisException(message: String, val failures: List<CloudProviderFailure>) : IllegalStateException(message)
