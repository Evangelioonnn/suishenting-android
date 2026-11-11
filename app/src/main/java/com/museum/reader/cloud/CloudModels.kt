package com.museum.reader.cloud

enum class CloudProvider(val label: String) {
    XUNFEI("讯飞开放平台"),
    BAIDU("百度智能云"),
}

data class CloudVoice(
    val id: String,
    val label: String,
    val description: String,
)

data class CloudSpeechPreferences(
    val enabled: Boolean = false,
    val primaryProvider: CloudProvider = CloudProvider.XUNFEI,
    val xunfeiVoice: String = "xiaoyan",
    val baiduVoice: String = "0",
    val dailyCharacterLimit: Int = 20_000,
)

data class XunfeiCredentials(
    val appId: String,
    val apiKey: String,
    val apiSecret: String,
) {
    val isComplete: Boolean get() = appId.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank()
}

data class BaiduCredentials(
    val appId: String,
    val apiKey: String,
    val secretKey: String,
) {
    val isComplete: Boolean get() = appId.isNotBlank() && apiKey.isNotBlank() && secretKey.isNotBlank()
}

data class CloudCredentials(
    val xunfei: XunfeiCredentials? = null,
    val baidu: BaiduCredentials? = null,
)

data class CloudSpeechUiState(
    val preferences: CloudSpeechPreferences = CloudSpeechPreferences(),
    val xunfeiConfigured: Boolean = false,
    val baiduConfigured: Boolean = false,
    val usedToday: Int = 0,
    val generationDone: Int = 0,
    val generationTotal: Int = 0,
    val generationDocumentTitle: String = "",
    val credentialError: String? = null,
)

val XUNFEI_VOICES = listOf(
    CloudVoice("xiaoyan", "讯飞小燕", "自然清晰的普通话女声"),
    CloudVoice("aisjiuxu", "讯飞许久", "沉稳的普通话男声"),
    CloudVoice("aisxping", "讯飞小萍", "亲切柔和的普通话女声"),
    CloudVoice("aisjinger", "讯飞小婧", "明亮年轻的普通话女声"),
)

val BAIDU_VOICES = listOf(
    CloudVoice("0", "度小美", "标准普通话女声"),
    CloudVoice("1", "度小宇", "标准普通话男声"),
    CloudVoice("3", "度逍遥", "更有感情的普通话男声"),
    CloudVoice("4", "度丫丫", "更有感情的普通话女声"),
)
