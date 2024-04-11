package org.cloudfoundry.identity.uaa.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.OIDC10;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.UAA;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.cloudfoundry.identity.uaa.alias.EntityAliasFailedException;
import org.cloudfoundry.identity.uaa.alias.EntityAliasHandlerEnsureConsistencyTest;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneProvisioning;
import org.cloudfoundry.identity.uaa.zone.ZoneDoesNotExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class IdentityProviderAliasHandlerEnsureConsistencyTest extends EntityAliasHandlerEnsureConsistencyTest<IdentityProvider<?>> {
    @Mock
    private IdentityZoneProvisioning identityZoneProvisioning;
    @Mock
    private IdentityProviderProvisioning identityProviderProvisioning;
    private IdentityProviderAliasHandler idpAliasHandler;

    @BeforeEach
    void setUp() {
        idpAliasHandler = new IdentityProviderAliasHandler(
                identityZoneProvisioning,
                identityProviderProvisioning,
                false
        );
    }

    @Nested
    class ExistingAlias {
        @Nested
        class AliasFeatureEnabled {
            @BeforeEach
            void setUp() {
                arrangeAliasFeatureEnabled(true);
            }

            @Test
            void shouldPropagateChangesToExistingAlias() {
                final String aliasIdpId = UUID.randomUUID().toString();

                final IdentityProvider<?> existingIdp = buildEntityWithAliasProperties(aliasIdpId, customZoneId);
                final String originalIdpId = existingIdp.getId();

                final IdentityProvider<?> originalIdp = shallowCloneEntity(existingIdp);
                final String newName = "some-new-name";
                originalIdp.setName(newName);

                final IdentityProvider<?> aliasIdp = shallowCloneEntity(existingIdp);
                aliasIdp.setId(aliasIdpId);
                aliasIdp.setIdentityZoneId(customZoneId);
                aliasIdp.setAliasId(originalIdpId);
                aliasIdp.setAliasZid(UAA);
                when(identityProviderProvisioning.retrieve(aliasIdpId, customZoneId)).thenReturn(aliasIdp);

                final IdentityProvider<?> result = idpAliasHandler.ensureConsistencyOfAliasEntity(
                        originalIdp,
                        existingIdp
                );
                assertThat(result).isEqualTo(originalIdp);

                final ArgumentCaptor<IdentityProvider> aliasIdpArgumentCaptor = ArgumentCaptor.forClass(IdentityProvider.class);
                verify(identityProviderProvisioning).update(aliasIdpArgumentCaptor.capture(), eq(customZoneId));

                final IdentityProvider capturedAliasIdp = aliasIdpArgumentCaptor.getValue();
                assertThat(capturedAliasIdp.getAliasId()).isEqualTo(originalIdpId);
                assertThat(capturedAliasIdp.getAliasZid()).isEqualTo(UAA);
                assertThat(capturedAliasIdp.getId()).isEqualTo(aliasIdpId);
                assertThat(capturedAliasIdp.getIdentityZoneId()).isEqualTo(customZoneId);
                assertThat(capturedAliasIdp.getName()).isEqualTo(newName);
            }

            @Test
            void shouldThrow_WhenReferencedAliasIdpAndAliasZoneDoesNotExist() {
                final String aliasIdpId = UUID.randomUUID().toString();

                final IdentityProvider<?> existingIdp = buildEntityWithAliasProperties(aliasIdpId, customZoneId);

                final IdentityProvider<?> originalIdp = shallowCloneEntity(existingIdp);
                final String newName = "some-new-name";
                originalIdp.setName(newName);

                // dangling reference -> referenced alias IdP not present
                when(identityProviderProvisioning.retrieve(aliasIdpId, customZoneId)).thenReturn(null);

                // alias zone does not exist
                arrangeZoneDoesNotExist(customZoneId);

                assertThatExceptionOfType(EntityAliasFailedException.class).isThrownBy(() ->
                        idpAliasHandler.ensureConsistencyOfAliasEntity(originalIdp, existingIdp)
                );
            }

            @Test
            void shouldFixDanglingReferenceByCreatingNewAliasIdp() {
                final String initialAliasIdpId = UUID.randomUUID().toString();

                final IdentityProvider<?> existingIdp = buildEntityWithAliasProperties(
                        initialAliasIdpId,
                        customZoneId
                );
                final String originalIdpId = existingIdp.getId();

                final IdentityProvider<?> requestBody = shallowCloneEntity(existingIdp);
                final String newName = "some-new-name";
                requestBody.setName(newName);

                // dangling reference -> referenced alias IdP not present
                when(identityProviderProvisioning.retrieve(initialAliasIdpId, customZoneId)).thenReturn(null);

                // mock alias IdP creation
                final IdentityProvider<?> createdAliasIdp = shallowCloneEntity(requestBody);
                final String newAliasIdpId = UUID.randomUUID().toString();
                createdAliasIdp.setId(newAliasIdpId);
                createdAliasIdp.setIdentityZoneId(customZoneId);
                createdAliasIdp.setAliasId(originalIdpId);
                createdAliasIdp.setAliasZid(UAA);
                when(identityProviderProvisioning.create(
                        argThat(new EntityWithAliasMatcher<>(customZoneId, null, originalIdpId, UAA)),
                        eq(customZoneId)
                )).thenReturn(createdAliasIdp);

                // mock update of original IdP
                when(identityProviderProvisioning.update(argThat(new EntityWithAliasMatcher<>(UAA, originalIdpId, newAliasIdpId, customZoneId)), eq(UAA)))
                        .then(invocationOnMock -> invocationOnMock.getArgument(0));

                final IdentityProvider<?> result = idpAliasHandler.ensureConsistencyOfAliasEntity(
                        requestBody,
                        existingIdp
                );
                assertThat(result.getAliasId()).isEqualTo(newAliasIdpId);
                assertThat(result.getAliasZid()).isEqualTo(customZoneId);

                // should update original IdP with new aliasId
                final ArgumentCaptor<IdentityProvider> originalIdpCaptor = ArgumentCaptor.forClass(IdentityProvider.class);
                verify(identityProviderProvisioning).update(originalIdpCaptor.capture(), eq(UAA));
                final IdentityProvider<?> updatedOriginalIdp = originalIdpCaptor.getValue();
                assertThat(updatedOriginalIdp.getAliasId()).isEqualTo(newAliasIdpId);
            }
        }

        @Nested
        class AliasFeatureDisabled {
            @BeforeEach
            void setUp() {
                arrangeAliasFeatureEnabled(false);
            }

            @Test
            void shouldThrow_IfExistingEntityHasAlias() {
                final String aliasIdpId = UUID.randomUUID().toString();

                final IdentityProvider<?> existingIdp = buildEntityWithAliasProperties(aliasIdpId, customZoneId);

                final IdentityProvider<?> originalIdp = shallowCloneEntity(existingIdp);
                originalIdp.setAliasId(null);
                originalIdp.setAliasZid(null);
                originalIdp.setName("some-new-name");

                assertThatIllegalStateException().isThrownBy(() ->
                        idpAliasHandler.ensureConsistencyOfAliasEntity(originalIdp, existingIdp)
                ).withMessage("Performing update on entity with alias while alias feature is disabled.");
            }
        }
    }

    @Nested
    class NoExistingAlias {
        abstract class NoExistingAliasBase {
            @Test
            void shouldIgnore_AliasZidEmptyInOriginalIdp() {
                final IdentityProvider<?> existingIdp = buildEntityWithAliasProperties(null, null);

                final IdentityProvider<?> originalIdp = shallowCloneEntity(existingIdp);
                originalIdp.setName("some-new-name");

                final IdentityProvider<?> result = idpAliasHandler.ensureConsistencyOfAliasEntity(originalIdp, existingIdp);
                assertThat(result).isEqualTo(originalIdp);
            }
        }

        @Nested
        class AliasFeatureEnabled extends NoExistingAliasBase {
            @BeforeEach
            void setUp() {
                arrangeAliasFeatureEnabled(true);
            }

            @Test
            void shouldThrow_WhenAliasZoneDoesNotExist() {
                final IdentityProvider<?> existingIdp = buildEntityWithAliasProperties(null, null);

                final IdentityProvider<?> requestBody = shallowCloneEntity(existingIdp);
                requestBody.setAliasZid(customZoneId);

                arrangeZoneDoesNotExist(customZoneId);

                assertThatExceptionOfType(EntityAliasFailedException.class).isThrownBy(() ->
                        idpAliasHandler.ensureConsistencyOfAliasEntity(requestBody, existingIdp)
                );
            }

            @Test
            void shouldCreateNewAliasIdp_WhenAliasZoneExistsAndAliasPropertiesAreSet() {
                final IdentityProvider<?> existingIdp = buildEntityWithAliasProperties(null, null);

                final IdentityProvider<?> requestBody = shallowCloneEntity(existingIdp);
                requestBody.setAliasZid(customZoneId);

                final String aliasIdpId = UUID.randomUUID().toString();
                when(identityProviderProvisioning.create(any(), eq(customZoneId))).then(invocationOnMock -> {
                    final IdentityProvider<?> idp = invocationOnMock.getArgument(0);
                    idp.setId(aliasIdpId);
                    return idp;
                });

                when(identityProviderProvisioning.update(any(), eq(UAA)))
                        .then(invocationOnMock -> invocationOnMock.getArgument(0));

                final IdentityProvider<?> result = idpAliasHandler.ensureConsistencyOfAliasEntity(
                        requestBody,
                        existingIdp
                );
                assertThat(result.getAliasId()).isEqualTo(aliasIdpId);
                assertThat(result.getAliasZid()).isEqualTo(customZoneId);
            }
        }

        @Nested
        class AliasFeatureDisabled extends NoExistingAliasBase {
            @BeforeEach
            void setUp() {
                arrangeAliasFeatureEnabled(false);
            }
        }
    }

    private void arrangeAliasFeatureEnabled(final boolean enabled) {
        ReflectionTestUtils.setField(idpAliasHandler, "aliasEntitiesEnabled", enabled);
    }

    @Override
    protected IdentityProvider<?> shallowCloneEntity(final IdentityProvider<?> idp) {
        final IdentityProvider<AbstractIdentityProviderDefinition> cloneIdp = new IdentityProvider<>();
        cloneIdp.setId(idp.getId());
        cloneIdp.setName(idp.getName());
        cloneIdp.setOriginKey(idp.getOriginKey());
        cloneIdp.setConfig(idp.getConfig());
        cloneIdp.setType(idp.getType());
        cloneIdp.setCreated(idp.getCreated());
        cloneIdp.setLastModified(idp.getLastModified());
        cloneIdp.setIdentityZoneId(idp.getIdentityZoneId());
        cloneIdp.setAliasId(idp.getAliasId());
        cloneIdp.setAliasZid(idp.getAliasZid());
        cloneIdp.setActive(idp.isActive());
        assertThat(cloneIdp).isEqualTo(idp);
        return cloneIdp;
    }

    @Override
    protected IdentityProvider<?> buildEntityWithAliasProperties(final String aliasId, final String aliasZid) {
        final IdentityProvider<?> existingIdp = new IdentityProvider<>();
        existingIdp.setType(OIDC10);
        existingIdp.setId(UUID.randomUUID().toString());
        existingIdp.setIdentityZoneId(UAA);
        existingIdp.setAliasId(aliasId);
        existingIdp.setAliasZid(aliasZid);
        return existingIdp;
    }

    @Override
    protected void arrangeZoneDoesNotExist(final String zoneId) {
        when(identityZoneProvisioning.retrieve(zoneId))
                .thenThrow(new ZoneDoesNotExistsException("zone does not exist"));
    }
}
