package com.aios.callintelligence;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Explainable, offline first-pass risk scoring for caller (downlink) speech.
 *
 * This result is advisory only. It may update the in-call UI, but it is never
 * allowed to answer, disconnect, transfer, purchase, or disclose information.
 */
final class SpamRiskEngine {
    static final String LIKELY_LEGITIMATE = "likely_legitimate";
    static final String UNKNOWN = "unknown";
    static final String SUSPICIOUS = "suspicious";
    static final String HIGH_RISK = "high_risk";

    static final class Assessment {
        final int score;
        final String label;
        final String reasonCode;

        Assessment(int score, String label, String reasonCode) {
            this.score = score;
            this.label = label;
            this.reasonCode = reasonCode;
        }

        boolean differsFrom(Assessment other) {
            return other == null || score != other.score || !label.equals(other.label)
                    || !reasonCode.equals(other.reasonCode);
        }
    }

    private static final class Signal {
        final String id;
        final int weight;
        final List<String> phrases;

        Signal(String id, int weight, String... phrases) {
            this.id = id;
            this.weight = weight;
            this.phrases = List.of(phrases);
        }
    }

    private static final List<Signal> RISK_SIGNALS = List.of(
            new Signal("remote_access", 40,
                    "anydesk", "teamviewer", "remote access", "acceso remoto"),
            new Signal("gift_card_payment", 45,
                    "gift card", "gift cards", "tarjeta de regalo", "tarjetas de regalo"),
            new Signal("robocall_instruction", 35,
                    "press one", "press 1", "presione uno", "presione 1",
                    "extended warranty", "garantia extendida"),
            new Signal("credential_request", 30,
                    "verification code", "security code", "one time code", "password",
                    "social security number", "codigo de verificacion", "codigo de seguridad",
                    "contrasena", "numero de seguro social"),
            new Signal("money_transfer", 25,
                    "wire transfer", "bank transfer", "send money", "zelle", "cash app",
                    "transferencia bancaria", "envie dinero", "mandar dinero"),
            new Signal("coercive_threat", 25,
                    "you will be arrested", "warrant for your arrest", "legal action",
                    "account will be suspended", "sera arrestado", "orden de arresto",
                    "accion legal", "cuenta sera suspendida", "cuenta suspendida"),
            new Signal("cryptocurrency_payment", 20,
                    "bitcoin", "cryptocurrency", "crypto wallet", "criptomoneda",
                    "billetera de cripto"),
            new Signal("do_not_disconnect", 15,
                    "do not hang up", "stay on the line", "no cuelgue", "permanezca en la linea"),
            new Signal("authority_impersonation", 15,
                    "internal revenue service", "social security administration",
                    "fraud department", "police department", "servicio de impuestos internos",
                    "administracion del seguro social", "departamento de fraude", "policia"),
            new Signal("artificial_urgency", 10,
                    "act now", "immediately", "final warning", "last chance",
                    "actue ahora", "inmediatamente", "ultima advertencia", "ultima oportunidad")
    );

    private static final List<String> BUSINESS_INTENT_PHRASES = List.of(
            "appointment", "estimate", "quote", "service call", "repair", "schedule",
            "job site", "plumbing", "electrician", "roofing", "delivery",
            "cita", "presupuesto", "cotizacion", "reparacion", "programar",
            "sitio de trabajo", "plomeria", "electricista", "techo", "entrega");

    private final boolean knownContact;
    private final Set<String> observedRiskSignals = new HashSet<>();
    private final Set<String> observedBusinessSignals = new HashSet<>();
    private final Set<String> provisionalRiskSignals = new HashSet<>();
    private final Set<String> provisionalBusinessSignals = new HashSet<>();
    private Assessment current;

    SpamRiskEngine(boolean knownContact) {
        this.knownContact = knownContact;
        current = assessment();
    }

    synchronized Assessment observe(String text, String language) {
        return observeRevision(text, language, true);
    }

    synchronized Assessment observeRevision(String text, String language, boolean isFinal) {
        if (text == null || !("en".equals(language) || "es".equals(language))) {
            return current;
        }
        String normalized = normalize(text);
        Set<String> revisionRiskSignals = new HashSet<>();
        Set<String> revisionBusinessSignals = new HashSet<>();
        for (Signal signal : RISK_SIGNALS) {
            if (containsAny(normalized, signal.phrases)) {
                revisionRiskSignals.add(signal.id);
            }
        }
        for (String phrase : BUSINESS_INTENT_PHRASES) {
            if (normalized.contains(phrase)) {
                revisionBusinessSignals.add(phrase);
            }
        }
        provisionalRiskSignals.clear();
        provisionalBusinessSignals.clear();
        if (isFinal) {
            observedRiskSignals.addAll(revisionRiskSignals);
            observedBusinessSignals.addAll(revisionBusinessSignals);
        } else {
            provisionalRiskSignals.addAll(revisionRiskSignals);
            provisionalBusinessSignals.addAll(revisionBusinessSignals);
        }
        current = assessment();
        return current;
    }

    synchronized Assessment current() {
        return current;
    }

    private Assessment assessment() {
        int score = knownContact ? 0 : 5;
        List<Signal> matched = new ArrayList<>();
        for (Signal signal : RISK_SIGNALS) {
            if (observedRiskSignals.contains(signal.id)
                    || provisionalRiskSignals.contains(signal.id)) {
                score += signal.weight;
                matched.add(signal);
            }
        }
        score = Math.min(100, score);
        String label;
        if (score >= 75) {
            label = HIGH_RISK;
        } else if (score >= 50) {
            label = SUSPICIOUS;
        } else if (score <= 15 && (knownContact || businessSignalCount() >= 2)) {
            label = LIKELY_LEGITIMATE;
        } else {
            label = UNKNOWN;
        }
        matched.sort(Comparator.comparingInt((Signal item) -> item.weight).reversed());
        String reason = matched.isEmpty()
                ? (knownContact ? "known_contact" : businessSignalCount() >= 2
                        ? "business_intent" : "insufficient_evidence")
                : matched.get(0).id;
        return new Assessment(score, label, reason);
    }

    private int businessSignalCount() {
        Set<String> combined = new HashSet<>(observedBusinessSignals);
        combined.addAll(provisionalBusinessSignals);
        return combined.size();
    }

    private static boolean containsAny(String text, List<String> phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .toLowerCase(Locale.ROOT);
        return decomposed.replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
