package co.org.avance.ssi.sim

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private fun usage(): Nothing {
    System.err.println(
        """
        holder-sim — Holder App SIMULADA (no es hardware seguro) y escenarios extremo a extremo por la red real.
          authority-init --out authority.json --root-out lab-attestation-root.pem
          scenario wallet|did|credentials|vdr-off|tour-wallet|tour-did|tour-credentials --authority authority.json --domain D --base https://D:8444 --ca ca.crt
                   [--admin-url https://D:8443 --p12 admin.p12 --p12-pass P --admin-token T]   (credentials)
                   [--secrets-out archivo]   (escribe las formas de las claves privadas de PRUEBA para buscarlas en la base)
        """.trimIndent(),
    )
    exitProcess(2)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val o = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    fun req(k: String) = o[k] ?: run { System.err.println("Falta --$k"); usage() }
    when (args[0]) {
        "authority-init" -> {
            val a = LabAttestationAuthority.create()
            File(req("out")).writeText(a.export())
            File(req("root-out")).writeText(a.rootPem())
            println("Autoridad de attestation de LABORATORIO creada. Raíz en ${req("root-out")}")
        }
        "scenario" -> {
            val name = args.getOrNull(1) ?: usage()
            val opts = args.drop(2).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
            fun r(k: String) = opts[k] ?: run { System.err.println("Falta --$k"); usage() }
            val ctx = ScenarioContext(
                domain = r("domain"), base = r("base").trimEnd('/'), ca = r("ca"), authority = LabAttestationAuthority.load(File(r("authority")).readText()),
                adminUrl = opts["admin-url"]?.trimEnd('/'), adminP12 = opts["p12"], p12Pass = opts["p12-pass"] ?: "changeit", adminToken = opts["admin-token"], secretsOut = opts["secrets-out"],
            )
            val rep = Reporter()
            runBlocking {
                try {
                    when (name) {
                        "wallet" -> walletScenario(ctx, rep)
                        "did" -> didScenario(ctx, rep)
                        "credentials" -> credentialScenario(ctx, rep)
                        "vdr-off" -> vdrOffScenario(ctx, rep)
                        "tour-wallet" -> walletTour(ctx)
                        "tour-did" -> didTour(ctx)
                        "tour-credentials" -> credentialsTour(ctx)
                        else -> usage()
                    }
                } catch (e: Exception) {
                    // un escenario que se cae NO es un éxito: se reporta como fallo con la causa
                    rep.check("el escenario '$name' terminó sin excepciones", false, "${e.javaClass.simpleName}: ${e.message}")
                }
            }
            println("  RESULTADO $name: ${rep.pass} PASS · ${rep.fail} FAIL")
            exitProcess(if (rep.fail == 0) 0 else 1)
        }
        else -> usage()
    }
}
