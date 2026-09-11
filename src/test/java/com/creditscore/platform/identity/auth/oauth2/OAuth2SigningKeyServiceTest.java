package com.creditscore.platform.identity.auth.oauth2;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2SigningKeyServiceTest {

    private final OAuth2SigningKeyRepository repository = mock(OAuth2SigningKeyRepository.class);
    private final OAuth2SigningKeyService service = selfInjected(repository);

    /**
     * Production wiring resolves the {@code self} constructor parameter to a
     * {@code @Lazy} Spring AOP proxy of this same bean, so that calls like
     * {@code self::createAndPersist} actually go through the proxy and pick up
     * {@code @Transactional}. There's no Spring context in this plain unit test, so
     * this wires {@code self} to point back at the same instance directly — enough to
     * exercise the business logic these tests check, though (as noted in the class
     * javadoc) no mocked-repository test can verify the AOP/transactional wiring
     * itself.
     */
    private static OAuth2SigningKeyService selfInjected(OAuth2SigningKeyRepository repository) {
        try {
            OAuth2SigningKeyService service = new OAuth2SigningKeyService(repository, null);
            Field selfField = OAuth2SigningKeyService.class.getDeclaredField("self");
            selfField.setAccessible(true);
            selfField.set(service, service);
            return service;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void returnsTheExistingKeyWhenOneIsAlreadyPersisted() throws Exception {
        RSAKey original = new RSAKeyGenerator(2048).keyID("existing-kid").generate();
        OAuth2SigningKey stored = new OAuth2SigningKey(OAuth2SigningKey.PRIMARY_KEY_ID, original.toJSONString());
        when(repository.findById(OAuth2SigningKey.PRIMARY_KEY_ID)).thenReturn(Optional.of(stored));

        RSAKey result = service.getOrCreateSigningKey();

        assertThat(result.getKeyID()).isEqualTo("existing-kid");
        assertThat(result.isPrivate()).isTrue();
    }

    @Test
    void generatesAndPersistsANewKeyWhenNoneExists() {
        when(repository.findById(OAuth2SigningKey.PRIMARY_KEY_ID)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(OAuth2SigningKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RSAKey result = service.getOrCreateSigningKey();

        assertThat(result).isNotNull();
        assertThat(result.isPrivate()).isTrue();
        verify(repository, times(1)).saveAndFlush(any(OAuth2SigningKey.class));
    }

    @Test
    void recoversTheWinningKeyWhenTwoInstancesRaceToCreateIt() throws Exception {
        RSAKey winner = new RSAKeyGenerator(2048).keyID("winner-kid").generate();
        OAuth2SigningKey winnerRow = new OAuth2SigningKey(OAuth2SigningKey.PRIMARY_KEY_ID, winner.toJSONString());

        when(repository.findById(OAuth2SigningKey.PRIMARY_KEY_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerRow));
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(repository).saveAndFlush(any(OAuth2SigningKey.class));

        RSAKey result = service.getOrCreateSigningKey();

        assertThat(result.getKeyID()).isEqualTo("winner-kid");
    }

    @Test
    void isNewIsTrueForAFreshlyConstructedEntityAndFalseAfterAPersistOrLoadCallback() throws Exception {
        OAuth2SigningKey key = new OAuth2SigningKey("id", "{}");
        assertThat(key.isNew()).isTrue();

        var callback = OAuth2SigningKey.class.getDeclaredMethod("markNotNew");
        callback.setAccessible(true);
        callback.invoke(key);

        assertThat(key.isNew()).isFalse();
    }

    @Test
    void markNotNewIsWiredToBothJpaLifecycleCallbacks() throws Exception {
        var callback = OAuth2SigningKey.class.getDeclaredMethod("markNotNew");

        assertThat(callback.isAnnotationPresent(jakarta.persistence.PostLoad.class)).isTrue();
        assertThat(callback.isAnnotationPresent(jakarta.persistence.PostPersist.class)).isTrue();
    }
}
