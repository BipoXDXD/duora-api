package bipo.tech.testfixtures.teststyle;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import bipo.tech.duoraapi.waitlist.application.WaitlistService;

/**
 * Violação proposital para TestStyleRulesTest: troca um serviço do sistema sem nenhum campo. Fica
 * fora de bipo.tech.duoraapi para o TestStyleTest não a encontrar.
 */
@MockitoBean(types = WaitlistService.class)
public class ClassLevelMockitoBeanFixture {
}
