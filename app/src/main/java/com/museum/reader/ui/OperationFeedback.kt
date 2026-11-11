package com.museum.reader.ui

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import com.museum.reader.cloud.CloudServiceException
import com.museum.reader.cloud.CloudSynthesisException

enum class UserOperation(val title: String, val guidance: String) {
    GENERAL("操作未完成", "操作未完成，请返回后重试；请勿清除应用数据"),
    OPEN("文稿暂时无法打开", "无法打开文稿，请返回列表后重试"),
    IMPORT("文件未导入", "文件导入失败，请检查文件是否完整并重新选择；已有文稿保留"),
    CREATE("文稿未创建", "文稿创建失败，请检查存储空间后重试"),
    BACKUP_EXPORT("备份未保存", "备份未保存，请检查存储空间和所选位置后重新导出"),
    BACKUP_READ("备份未通过校验", "无法读取或校验备份，请检查文件是否完整后重新选择；本机资料未替换"),
    BACKUP_RESTORE("备份恢复未完成", "恢复未完成，请检查空间后重新选择备份；恢复记录和恢复点保留"),
    RECORD_START("无法开始录音", "无法开始录音，请检查麦克风权限及存储空间后重试"),
    RECORD_PROCESS("录音整理未完成", "录音整理未完成，请重新打开录音页查看待确认文件；原有效录音保留"),
    RECORD_SAVE("录音保存未完成", "录音保存未完成，请检查空间后重试；原有效录音和本次文件保留"),
    RECORD_DISCARD("本次录音未能放弃", "无法放弃本次录音，请停止试听后重试；请勿手动删除原文件"),
    AUDIO("音频处理未完成", "音频处理失败，请检查所属文稿及存储空间后重试"),
    STORAGE("存储操作未完成", "存储操作未完成，请检查空间后重试；待处理记录保留"),
    CLOUD("云声音任务未完成", "云声音任务未完成，请检查网络、服务授权和额度后重试；已完成缓存保留"),
    CREDENTIALS("云配置未保存", "云配置保存失败，请重试；请勿清除应用数据或发送密钥"),
    SETTINGS("设置未保存", "设置保存失败，请检查存储空间后重试")
}

data class OperationFailure(val operation: UserOperation, val message: String)

/** Only application validation messages are suitable for direct display. Platform details are not. */
internal fun operationMessage(error: Throwable, operation: UserOperation): String {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    if (error is CloudServiceException) return checkNotNull(error.message)
    if (error is CloudSynthesisException && error.failures.isNotEmpty()) return error.failures.joinToString("；") {
        val guidance = operationMessage(it.error, UserOperation.CLOUD)
        if (it.error is CloudServiceException) guidance else it.provider.label + "：" + guidance
    }
    if (causes.any { it is SecurityException }) return when (operation) {
        UserOperation.RECORD_START -> "未获得麦克风权限，请在应用权限设置中允许后重试"
        else -> "无权访问所选位置，请重新选择有访问权限的文件或目录后重试"
    }
    if (operation == UserOperation.CLOUD) {
        if (causes.any { it is SocketTimeoutException }) return "云声音请求超时，请检查网络后重试；已完成缓存保留"
        if (causes.any { it is UnknownHostException }) return "暂时无法连接云服务，请检查网络后重试；已有缓存可继续使用"
    }
    if (causes.any { it is IOException || it.javaClass.name.startsWith("android.database.") }) return operation.guidance
    val message = error.message.orEmpty()
    if ((error is IllegalArgumentException || error is IllegalStateException) &&
        message.length in 1..240 && '\n' !in message && '\r' !in message &&
        approvedMessageStarts.any(message::startsWith) && !technicalOrSensitive.containsMatchIn(message)) return message
    return operation.guidance
}

private val approvedMessageStarts = listOf(
    "文稿", "正文", "文件", "文字编码", "暂不支持旧版", "这不是有效的 DOCX", "DOCX ",
    "无法识别文件格式", "无法读取所选文件", "无法写入所选位置", "没有找到", "请输入", "请先", "已有",
    "有待确认录音", "有音频", "手机空间不足", "备份", "音频", "新格式音频", "恢复", "无法创建",
    "无法清理", "无法移除", "本机文稿已变化", "录音", "这份录音", "该录音", "待确认", "该文稿", "所选",
    "试听文字", "已达到今日", "没有可用的联网", "密钥读取失败", "密钥不可用"
)
private val technicalOrSensitive = Regex(
    "(?i)sqlite|database|exception|java\\.|android\\.|/data/|/storage/|[a-z]:[\\\\/]|https?://|" +
        "(?:api[_ -]?key|secret|token|authorization|password)\\s*[:=]|private-[a-z]"
)

internal inline fun <T> runOperationCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
