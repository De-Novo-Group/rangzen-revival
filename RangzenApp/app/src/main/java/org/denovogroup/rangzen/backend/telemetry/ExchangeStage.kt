package org.denovogroup.rangzen.backend.telemetry

/**
 * Stages of the exchange protocol for tracking where failures occur.
 * Each stage maps to a short code for compact storage.
 */
enum class ExchangeStage(val code: String) {
    // Discovery phase
    DISCOVERY("discovery"),

    // Connection phase
    CONNECTING("connecting"),
    CONNECTED("connected"),

    // BLE-specific setup
    MTU_NEGOTIATION("mtu_nego"),
    SERVICE_DISCOVERY("svc_discovery"),
    CHARACTERISTIC_SETUP("char_setup"),

    // Protocol handshake
    PROTOCOL_VERSION("proto_ver"),
    PSI_INIT("psi_init"),
    PSI_EXCHANGE("psi_exchange"),
    PSI_COMPLETE("psi_complete"),
    TRUST_COMPUTED("trust_computed"),

    // Message exchange
    SENDING_MESSAGES("sending"),
    RECEIVING_MESSAGES("receiving"),

    // Cleanup
    DISCONNECTING("disconnecting"),
    COMPLETE("complete")
}
