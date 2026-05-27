package org.tan.cdntest

import android.net.http.SslCertificate
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.security.auth.x500.X500Principal

object CertHelper {

    data class CertInfo(
        val issuedTo: CertSubject,
        val issuedBy: CertSubject,
        val notBefore: String,
        val notAfter: String,
        val serialNumber: String
    )

    data class CertSubject(
        val cn: String,
        val o: String,
        val ou: String
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun parseCert(cert: SslCertificate): CertInfo? {
        val x509 = getX509(cert) ?: return parseFromSslCert(cert)
        return parseX509(x509)
    }

    private fun getX509(cert: SslCertificate): X509Certificate? {
        return try {
            val method = cert.javaClass.getMethod("getX509Certificate")
            when (val result = method.invoke(cert)) {
                is X509Certificate -> result
                is android.os.Bundle -> {
                    val encoded = result.get("x509-certificate") as? ByteArray ?: return null
                    CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(encoded)) as? X509Certificate
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseX509(cert: X509Certificate): CertInfo {
        return CertInfo(
            issuedTo = parseX500Name(cert.subjectX500Principal),
            issuedBy = parseX500Name(cert.issuerX500Principal),
            notBefore = try { dateFormat.format(cert.notBefore) } catch (_: Exception) { "未知" },
            notAfter = try { dateFormat.format(cert.notAfter) } catch (_: Exception) { "未知" },
            serialNumber = cert.serialNumber?.toString(16)?.uppercase() ?: "未知"
        )
    }

    @Suppress("DEPRECATION")
    private fun parseFromSslCert(cert: SslCertificate): CertInfo? {
        val toDName = cert.issuedTo ?: return null
        val byDName = cert.issuedBy ?: return null
        return CertInfo(
            issuedTo = parseDName(toDName.dName),
            issuedBy = parseDName(byDName.dName),
            notBefore = try { cert.validNotBefore?.let { dateFormat.format(it) } } catch (_: Exception) { null } ?: "未知",
            notAfter = try { cert.validNotAfter?.let { dateFormat.format(it) } } catch (_: Exception) { null } ?: "未知",
            serialNumber = "未知"
        )
    }

    private fun parseX500Name(principal: X500Principal): CertSubject {
        val name = principal.name
        return CertSubject(
            cn = extractField(name, "CN"),
            o = extractField(name, "O"),
            ou = extractField(name, "OU")
        )
    }

    private fun parseDName(dName: String): CertSubject {
        return CertSubject(
            cn = extractField(dName, "CN"),
            o = extractField(dName, "O"),
            ou = extractField(dName, "OU")
        )
    }

    private fun extractField(dn: String, field: String): String {
        // 处理带引号和不带引号的值
        val regex = Regex("""(?:^|,\s*)${field}\s*=\s*("(?:[^"\\]|\\.)*"|[^,]+)""")
        val match = regex.find(dn) ?: return ""
        return match.groupValues[1].removeSurrounding("\"").trim()
    }

    fun formatCertInfo(info: CertInfo): String {
        return buildString {
            appendLine("【颁发对象】")
            if (info.issuedTo.cn.isNotEmpty()) appendLine("  公用名 (CN): ${info.issuedTo.cn}")
            if (info.issuedTo.o.isNotEmpty()) appendLine("  组织 (O): ${info.issuedTo.o}")
            if (info.issuedTo.ou.isNotEmpty()) appendLine("  组织单位 (OU): ${info.issuedTo.ou}")
            appendLine()
            appendLine("【颁发者】")
            if (info.issuedBy.cn.isNotEmpty()) appendLine("  公用名 (CN): ${info.issuedBy.cn}")
            if (info.issuedBy.o.isNotEmpty()) appendLine("  组织 (O): ${info.issuedBy.o}")
            appendLine()
            appendLine("【有效期】")
            appendLine("  颁发日期: ${info.notBefore}")
            appendLine("  截止日期: ${info.notAfter}")
            if (info.serialNumber != "未知") {
                appendLine()
                appendLine("【序列号】")
                appendLine("  ${info.serialNumber}")
            }
        }
    }
}
