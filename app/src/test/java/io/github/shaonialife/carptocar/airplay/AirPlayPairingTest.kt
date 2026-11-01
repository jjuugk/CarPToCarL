package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * 配对栈端到端回环：MockReceiver 严格按参考接收端（xcertplay PairSetup/PairVerify/Srp6a）
 * 的算法实现服务端，验证我们的客户端能完整走通 pair-setup + pair-verify，
 * 且双方派生的控制密钥方向一致。
 */
class AirPlayPairingTest {
    private val random = SecureRandom()

    @Test
    fun pairSetupThenPairVerifyAgainstMockReceiver() {
        val receiver = MockReceiver()
        val transport = TlvTransport { path, body -> receiver.handle(path, body) }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val identity = ControllerIdentity("test-controller-id", keyPair.privateKey, keyPair.publicKey)

        val receiverIdentity = PairSetupClient(identity, transport) { }.run()
        assertNotNull(receiverIdentity)
        assertEquals(receiver.pairingId, receiverIdentity!!.pairingId)
        assertArrayEquals(receiver.staticKey.publicKey, receiverIdentity.publicKey)
        assertTrue("接收端应保存控制器公钥", receiver.pairings.containsKey(identity.pairingId))

        val keys = PairVerifyClient(identity, transport) { }.run(receiver.staticKey.publicKey)
        assertEquals(32, keys.phoneWriteKey.size)
        assertEquals(32, keys.phoneReadKey.size)

        // 方向交叉验证：接收端 readKey 解我们发出的方向，writeKey 是它发给我们的方向
        assertNotNull("接收端应完成 pair-verify", receiver.lastReadKey)
        assertArrayEquals(receiver.lastReadKey, keys.phoneWriteKey)
        assertArrayEquals(receiver.lastWriteKey, keys.phoneReadKey)
    }

    /**
     * 日志 221747 的原样现场：pair-setup M4 回 `{Error:2, State:4}`。
     *
     * 旧代码只找 `TYPE_PROOF`，找不到就报「M4 缺少 proof」——真实原因（车机拒绝认证）
     * 从来没进过日志。现在必须抛带错误码的 [PairingRejectedException]，且判为确定性失败。
     */
    @Test
    fun pairSetupM4RejectionCarriesErrorCode() {
        val receiver = MockReceiver()
        val transport =
            TlvTransport { path, body ->
                if (path == "/pair-setup" && stateOf(body) == 3) {
                    errorReply(state = 4, code = 2)
                } else {
                    receiver.handle(path, body)
                }
            }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val identity = ControllerIdentity("c", keyPair.privateKey, keyPair.publicKey)

        val error =
            assertThrows(PairingRejectedException::class.java) {
                PairSetupClient(identity, transport) { }.run()
            }
        assertEquals(PairingStage.PAIR_SETUP, error.stage)
        assertEquals(PairingErrorCode.AUTHENTICATION, error.code)
        assertEquals(2, error.rawCode)
        assertEquals(4, error.state)
        assertTrue("Error=2 是车机状态，重试无意义", error.deterministic)
    }

    /**
     * 最严重的一条历史 bug：pair-verify M4 回 `{Error:2, State:4}` 时，
     * 旧代码只判 `State == 4`（成功标记）→ **把拒绝读成成功**，派生两把对不上的密钥，
     * 后续加密 SETUP 被车机静默丢弃，最后降级明文（无共享密钥 → 没有声音、没有触摸）。
     */
    @Test
    fun pairVerifyM4RejectionIsNotTreatedAsSuccess() {
        val receiver = MockReceiver()
        val transport =
            TlvTransport { path, body ->
                if (path == "/pair-verify" && stateOf(body) == 3) {
                    errorReply(state = 4, code = 2)
                } else {
                    receiver.handle(path, body)
                }
            }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val identity = ControllerIdentity("c", keyPair.privateKey, keyPair.publicKey)
        PairSetupClient(identity, transport) { }.run()

        val error =
            assertThrows(PairingRejectedException::class.java) {
                PairVerifyClient(identity, transport) { }.run(receiver.staticKey.publicKey)
            }
        assertEquals(PairingStage.PAIR_VERIFY, error.stage)
        assertEquals(PairingErrorCode.AUTHENTICATION, error.code)
        assertTrue(error.deterministic)
        // 拒绝时不得留下任何"密钥已建立"的假象
        assertNull(receiver.lastReadKey)
    }

    /** pair-verify M2 就回错误（车机连临时公钥都不给）：也要带错误码，不能报「缺少 publicKey」。 */
    @Test
    fun pairVerifyM2RejectionCarriesErrorCode() {
        val receiver = MockReceiver()
        val transport =
            TlvTransport { path, body ->
                if (path == "/pair-verify") errorReply(state = 4, code = 2) else receiver.handle(path, body)
            }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val identity = ControllerIdentity("c", keyPair.privateKey, keyPair.publicKey)

        val error =
            assertThrows(PairingRejectedException::class.java) {
                PairVerifyClient(identity, transport) { }.run()
            }
        assertEquals(PairingStage.PAIR_VERIFY, error.stage)
        assertEquals(PairingErrorCode.AUTHENTICATION, error.code)
    }

    /** `{Error:3}`（退避）不是确定性失败：车机只是要求「等会再来」。 */
    @Test
    fun backoffIsNotDeterministic() {
        val receiver = MockReceiver()
        val transport =
            TlvTransport { path, body ->
                if (path == "/pair-setup") errorReply(state = 2, code = 3) else receiver.handle(path, body)
            }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val identity = ControllerIdentity("c", keyPair.privateKey, keyPair.publicKey)

        val error =
            assertThrows(PairingRejectedException::class.java) {
                PairSetupClient(identity, transport) { }.run()
            }
        assertEquals(PairingErrorCode.BACKOFF, error.code)
        assertFalse(error.deterministic)
        assertTrue(PairingPolicy.shouldRetrySameStage(error.code))
    }

    private fun stateOf(body: ByteArray): Int = Tlv8.decode(body)[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt()?.and(0xFF) ?: 0

    /** 真机拒绝应答的形状：State 仍是「正常值」，错误码单独在 TYPE_ERROR。 */
    private fun errorReply(
        state: Int,
        code: Int,
    ): ByteArray =
        Tlv8.encode(
            listOf(
                Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(state.toByte())),
                Tlv8.Item(Tlv8.TYPE_ERROR, byteArrayOf(code.toByte())),
            ),
        )

    @Test
    fun tlv8FragmentationRoundTrip() {        val big = ByteArray(600) { (it % 251).toByte() }
        val encoded =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(1)),
                    Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, big),
                ),
            )
        val decoded = Tlv8.decode(encoded)
        assertEquals(1, decoded[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt())
        assertArrayEquals(big, decoded[Tlv8.TYPE_PUBLIC_KEY])
    }

    /** 按参考接收端算法实现的服务端。 */
    private class MockReceiver {
        val staticKey = AirPlayCrypto.ed25519Generate()
        val pairingId = "3e6f5739-b2bf-4ebf-bb87-5b6b57cc42e5"
        val pairings = HashMap<String, ByteArray>()
        var lastReadKey: ByteArray? = null
            private set
        var lastWriteKey: ByteArray? = null
            private set

        private val random = SecureRandom()

        // SRP 服务端状态
        private var salt = ByteArray(0)
        private var publicKeyB = BigInteger.ZERO
        private var verifier = BigInteger.ZERO
        private var privateB = BigInteger.ZERO
        private var sessionKey = ByteArray(0)

        // pair-verify 状态
        private var pvEphemeralPublic = ByteArray(0)
        private var pvShared = ByteArray(0)
        private var pvEncryptionKey = ByteArray(0)

        fun handle(
            path: String,
            body: ByteArray,
        ): ByteArray {
            val tlv = Tlv8.decode(body)
            val state = tlv[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            return when (path) {
                "/pair-setup" ->
                    when (state) {
                        1 -> setupM2()
                        3 -> setupM4(tlv)
                        5 -> setupM6(tlv)
                        else -> error(state)
                    }

                "/pair-verify" ->
                    when (state) {
                        1 -> verifyM2(tlv)
                        3 -> verifyM4(tlv)
                        else -> error(state)
                    }

                else -> error(state)
            }
        }

        private fun setupM2(): ByteArray {
            salt = ByteArray(16).also(random::nextBytes)
            val identifier = Srp6aClient.USERNAME.toByteArray()
            val x =
                Srp6aClient.toBigInt(
                    AirPlayCrypto.sha512(
                        salt,
                        AirPlayCrypto.sha512(identifier, byteArrayOf(':'.code.toByte()), Srp6aClient.SETUP_CODE.toByteArray()),
                    ),
                )
            verifier = Srp6aClient.generator().modPow(x, Srp6aClient.modulus())
            privateB = Srp6aClient.toBigInt(ByteArray(32).also(random::nextBytes))
            publicKeyB =
                (
                    Srp6aClient.multiplier() * verifier +
                        Srp6aClient.generator().modPow(
                            privateB,
                            Srp6aClient.modulus(),
                        )
                ).mod(Srp6aClient.modulus())
            return Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(2)),
                    Tlv8.Item(Tlv8.TYPE_SALT, salt),
                    Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, Srp6aClient.pad(publicKeyB)),
                ),
            )
        }

        private fun setupM4(tlv: Map<Int, ByteArray>): ByteArray {
            val publicKeyA = tlv[Tlv8.TYPE_PUBLIC_KEY] ?: return error(4)
            val clientProof = tlv[Tlv8.TYPE_PROOF] ?: return error(4)
            val a = Srp6aClient.toBigInt(publicKeyA)
            val u = Srp6aClient.toBigInt(AirPlayCrypto.sha512(Srp6aClient.pad(a), Srp6aClient.pad(publicKeyB)))
            val s = (a * verifier.modPow(u, Srp6aClient.modulus())).mod(Srp6aClient.modulus()).modPow(privateB, Srp6aClient.modulus())
            sessionKey = AirPlayCrypto.sha512(Srp6aClient.toBytes(s))

            val hashN = AirPlayCrypto.sha512(Srp6aClient.toBytes(Srp6aClient.modulus()))
            val hashG = AirPlayCrypto.sha512(Srp6aClient.toBytes(Srp6aClient.generator()))
            val hashXor = ByteArray(hashN.size)
            for (index in hashN.indices) {
                hashXor[index] = (hashN[index].toInt() xor hashG[index].toInt()).toByte()
            }
            val identifier = Srp6aClient.USERNAME.toByteArray()
            val expected =
                AirPlayCrypto.sha512(
                    hashXor,
                    AirPlayCrypto.sha512(identifier),
                    salt,
                    Srp6aClient.pad(a),
                    Srp6aClient.pad(publicKeyB),
                    sessionKey,
                )
            if (!expected.contentEquals(clientProof)) return error(4)
            val serverProof = AirPlayCrypto.sha512(Srp6aClient.pad(a), clientProof, sessionKey)
            return Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(4)),
                    Tlv8.Item(Tlv8.TYPE_PROOF, serverProof),
                ),
            )
        }

        private fun setupM6(tlv: Map<Int, ByteArray>): ByteArray {
            val encrypted = tlv[Tlv8.TYPE_ENCRYPTED_DATA] ?: return error(6)
            val encryptionKey = hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
            val sub = Tlv8.decode(AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg05"), encrypted))
            val controllerId = sub[Tlv8.TYPE_IDENTIFIER] ?: return error(6)
            val controllerLtpk = sub[Tlv8.TYPE_PUBLIC_KEY] ?: return error(6)
            val controllerSignature = sub[Tlv8.TYPE_SIGNATURE] ?: return error(6)

            val controllerSignKey = hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
            if (!AirPlayCrypto.ed25519Verify(controllerLtpk, controllerSignKey + controllerId + controllerLtpk, controllerSignature)) {
                return error(6)
            }
            pairings[controllerId.toString(Charsets.UTF_8)] = controllerLtpk

            val accessoryId = pairingId.toByteArray(Charsets.UTF_8)
            val accessorySignKey = hkdf("Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
            val signature = AirPlayCrypto.ed25519Sign(staticKey.privateKey, accessorySignKey + accessoryId + staticKey.publicKey)
            val subResponse =
                Tlv8.encode(
                    listOf(
                        Tlv8.Item(Tlv8.TYPE_IDENTIFIER, accessoryId),
                        Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, staticKey.publicKey),
                        Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                    ),
                )
            return Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(6)),
                    Tlv8.Item(
                        Tlv8.TYPE_ENCRYPTED_DATA,
                        AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg06"), subResponse),
                    ),
                ),
            )
        }

        private fun verifyM2(tlv: Map<Int, ByteArray>): ByteArray {
            val clientEphemeral = tlv[Tlv8.TYPE_PUBLIC_KEY] ?: return error(2)
            pvClientEphemeral = clientEphemeral
            val pair = AirPlayCrypto.x25519Generate()
            pvEphemeralPublic = pair.publicKey
            pvShared = AirPlayCrypto.x25519Shared(pair.privateKey, clientEphemeral)
            pvEncryptionKey = hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")

            val accessoryId = pairingId.toByteArray(Charsets.UTF_8)
            val signature =
                AirPlayCrypto.ed25519Sign(
                    staticKey.privateKey,
                    pair.publicKey + accessoryId + clientEphemeral,
                )
            val sub =
                Tlv8.encode(
                    listOf(
                        Tlv8.Item(Tlv8.TYPE_IDENTIFIER, accessoryId),
                        Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                    ),
                )
            return Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(2)),
                    Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, pair.publicKey),
                    Tlv8.Item(
                        Tlv8.TYPE_ENCRYPTED_DATA,
                        AirPlayCrypto.chachaSeal(pvEncryptionKey, AirPlayCrypto.nonceLabel("PV-Msg02"), sub),
                    ),
                ),
            )
        }

        private fun verifyM4(tlv: Map<Int, ByteArray>): ByteArray {
            val encrypted = tlv[Tlv8.TYPE_ENCRYPTED_DATA] ?: return error(4)
            val sub = Tlv8.decode(AirPlayCrypto.chachaOpen(pvEncryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"), encrypted))
            val controllerId = sub[Tlv8.TYPE_IDENTIFIER] ?: return error(4)
            val controllerSignature = sub[Tlv8.TYPE_SIGNATURE] ?: return error(4)
            val controllerLtpk = pairings[controllerId.toString(Charsets.UTF_8)] ?: return error(4)

            // 客户端临时公钥需从 M1 找回：简化测试路径里客户端把它放在 identifier 前——
            // 实际按参考算法应为 clientEphemeral || controllerId || serverEphemeral。
            // 这里从 pair-verify 会话中重建（测试中我们保存了它）。
            val clientEphemeral = pvClientEphemeral ?: return error(4)
            if (!AirPlayCrypto.ed25519Verify(controllerLtpk, clientEphemeral + controllerId + pvEphemeralPublic, controllerSignature)) {
                return error(4)
            }
            lastReadKey = hkdf("Control-Salt", "Control-Write-Encryption-Key")
            lastWriteKey = hkdf("Control-Salt", "Control-Read-Encryption-Key")
            return Tlv8.encode(listOf(Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(4))))
        }

        private var pvClientEphemeral: ByteArray? = null

        private fun hkdf(
            salt: String,
            info: String,
        ): ByteArray {
            val material = if (pvShared.isNotEmpty()) pvShared else sessionKey
            return AirPlayCrypto.hkdfSha512(material, salt.toByteArray(), info.toByteArray())
        }

        private fun error(state: Int): ByteArray =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(state.toByte())),
                    Tlv8.Item(Tlv8.TYPE_ERROR, byteArrayOf(2)),
                ),
            )
    }
}
