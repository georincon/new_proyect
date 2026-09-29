rootProject.name = "vdr-ssi"

include(
    "did-core", "did-resolver", "vdr-service", "did-tools",
    // ERSo 2026-001/002/003 (cartera, credenciales, DID del titular)
    "credentials-core", "wallet-core", "wallet-sim", "wallet-service", "credential-service",
)
