package com.museum.reader.ui

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import com.museum.reader.cloud.*

class OperationFeedbackTest {
    @Test fun ioAndTechnicalMessagesNeverExposePathsOrDiagnostics() {
        for (failure in listOf(IOException("/data/user/0/private-user-file"),
            IllegalStateException("备份失败 SQLITE_CONSTRAINT private-sql"),
            IllegalArgumentException("备份错误 https://synthetic.example/?token=synthetic-test"),
            IllegalStateException("备份错误\njava.lang.Exception"))) {
            assertEquals(UserOperation.BACKUP_EXPORT.guidance, operationMessage(failure, UserOperation.BACKUP_EXPORT))
        }
    }
    @Test fun explicitValidationKeepsTheActionableBusinessReason() {
        for (message in listOf("文件超过 20 MB，请精简后再导入", "文字编码损坏或无法识别，请另存为 UTF-8 后导入",
            "备份包版本过新，请升级应用", "手机空间不足以生成安全备份", "有待确认录音，请先保存或放弃")) {
            assertEquals(message, operationMessage(IllegalArgumentException(message), UserOperation.IMPORT))
        }
    }
    @Test fun cancellationAlwaysPropagatesInsteadOfBecomingAnErrorMessage() {
        val cancel = CancellationException("synthetic cancellation")
        try { runOperationCatching { throw cancel }; fail("Cancellation swallowed") }
        catch (actual: CancellationException) { assertSame(cancel, actual) }
        try { operationMessage(IllegalStateException("wrapped", cancel), UserOperation.CLOUD); fail("Cancellation swallowed") }
        catch (actual: CancellationException) { assertSame(cancel, actual) }
    }
    @Test fun permissionsTimeoutAndConnectionFailuresOfferRelevantRecovery() {
        assertTrue(operationMessage(SecurityException("private"), UserOperation.RECORD_START).contains("麦克风权限"))
        assertTrue(operationMessage(SecurityException("private"), UserOperation.BACKUP_EXPORT).contains("重新选择"))
        assertTrue(operationMessage(SocketTimeoutException("private"), UserOperation.CLOUD).contains("超时"))
        assertTrue(operationMessage(UnknownHostException("private-host"), UserOperation.CLOUD).contains("检查网络"))
    }

    @Test fun cloudAggregateRetainsDistinctPerProviderGuidanceWithoutRawBodies() {
        val error = CloudSynthesisException("synthetic private-response", listOf(
            CloudProviderFailure(CloudProvider.BAIDU, CloudServiceException.http(CloudProvider.BAIDU, 401)),
            CloudProviderFailure(CloudProvider.XUNFEI, SocketTimeoutException("private-synthetic-url"))))
        val message = operationMessage(error, UserOperation.CLOUD)
        assertTrue(message.contains("鉴权")); assertTrue(message.contains("超时"))
        assertFalse(message.contains("private-"))
        assertTrue(operationMessage(CloudServiceException.http(CloudProvider.BAIDU, 429), UserOperation.CLOUD).contains("额度"))
    }
}
