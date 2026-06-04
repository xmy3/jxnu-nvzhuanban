package cn.jxnu.nvzhuanban.data.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 锁定 [withSessionRecovery] 的容错契约 —— 13/15 个登录态 Repository 经
 * [JwcClient.getHtmlAuth] / [JwcClient.postHtmlAuth] 间接依赖它。这里注入 fake 的
 * reauth / notify，脱离 OkHttp 与 AuthRepository 单例，纯逻辑验证
 * 「只重放一次 / reauth 失败才广播」这套被 CLAUDE.md 反复强调的语义。
 */
class JwcClientSessionRecoveryTest {

    private fun sessionExpired() = JwcException(JwcError.SessionExpired)
    private fun otherJwcError() = JwcException(JwcError.EmptyResponse)

    @Test
    fun `block succeeds - no reauth, no notify`() = runBlocking {
        var reauthCalls = 0
        var notifyCalls = 0
        val result = withSessionRecovery(
            reauth = { reauthCalls++; true },
            notifyExpired = { notifyCalls++ },
            block = { "ok" },
        )
        assertEquals("ok", result)
        assertEquals(0, reauthCalls)
        assertEquals(0, notifyCalls)
    }

    @Test
    fun `non session-expired error is rethrown without reauth`() {
        var reauthCalls = 0
        var notifyCalls = 0
        val ex = assertThrows(JwcException::class.java) {
            runBlocking {
                withSessionRecovery(
                    reauth = { reauthCalls++; true },
                    notifyExpired = { notifyCalls++ },
                    block = { throw otherJwcError() },
                )
            }
        }
        assertEquals(JwcError.EmptyResponse, ex.error)
        assertEquals(0, reauthCalls)
        assertEquals(0, notifyCalls)
    }

    @Test
    fun `session expired then reauth fails - notify once and rethrow`() {
        var reauthCalls = 0
        var notifyCalls = 0
        val ex = assertThrows(JwcException::class.java) {
            runBlocking {
                withSessionRecovery(
                    reauth = { reauthCalls++; false },
                    notifyExpired = { notifyCalls++ },
                    block = { throw sessionExpired() },
                )
            }
        }
        assertEquals(JwcError.SessionExpired, ex.error)
        assertEquals(1, reauthCalls)
        assertEquals(1, notifyCalls)
    }

    @Test
    fun `session expired then reauth succeeds then replay succeeds`() = runBlocking {
        var reauthCalls = 0
        var notifyCalls = 0
        var blockCalls = 0
        val result = withSessionRecovery(
            reauth = { reauthCalls++; true },
            notifyExpired = { notifyCalls++ },
            block = {
                blockCalls++
                if (blockCalls == 1) throw sessionExpired() else "replayed"
            },
        )
        assertEquals("replayed", result)
        assertEquals(2, blockCalls)   // 原始 + 重放各一次
        assertEquals(1, reauthCalls)
        assertEquals(0, notifyCalls)  // 重放成功不广播
    }

    @Test
    fun `replay still session expired - notify once and no second reauth`() {
        var reauthCalls = 0
        var notifyCalls = 0
        var blockCalls = 0
        val ex = assertThrows(JwcException::class.java) {
            runBlocking {
                withSessionRecovery(
                    reauth = { reauthCalls++; true },
                    notifyExpired = { notifyCalls++ },
                    block = { blockCalls++; throw sessionExpired() },
                )
            }
        }
        assertEquals(JwcError.SessionExpired, ex.error)
        assertEquals(2, blockCalls)   // 重放了一次
        assertEquals(1, reauthCalls)  // 只 reauth 一次，不二次
        assertEquals(1, notifyCalls)  // 二次仍过期才广播
    }

    @Test
    fun `replay throws other error - rethrow without notify`() {
        var notifyCalls = 0
        var blockCalls = 0
        val ex = assertThrows(JwcException::class.java) {
            runBlocking {
                withSessionRecovery(
                    reauth = { true },
                    notifyExpired = { notifyCalls++ },
                    block = {
                        blockCalls++
                        if (blockCalls == 1) throw sessionExpired() else throw otherJwcError()
                    },
                )
            }
        }
        assertEquals(JwcError.EmptyResponse, ex.error)  // 重放的异常原样透传
        assertEquals(2, blockCalls)
        assertEquals(0, notifyCalls)  // 非 SessionExpired 不广播
    }
}
