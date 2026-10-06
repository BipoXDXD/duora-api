package bipo.tech.testfixtures.modules.client;

import bipo.tech.testfixtures.modules.owner.internal.InternalType;

/** Violação proposital para ArchitectureRulesTest: outro módulo alcança um interno de "owner". */
public record UsesInternalType(InternalType internal) {
}
