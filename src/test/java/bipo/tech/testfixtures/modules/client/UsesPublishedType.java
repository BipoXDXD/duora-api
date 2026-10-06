package bipo.tech.testfixtures.modules.client;

import bipo.tech.testfixtures.modules.owner.PublishedType;

/** Uso permitido: outro módulo depende só da API publicada de "owner". */
public record UsesPublishedType(PublishedType published) {
}
