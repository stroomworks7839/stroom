package stroom.security.impl.db;

import stroom.docref.DocRef;
import stroom.security.impl.BasicDocPermissions;
import stroom.security.impl.DocumentPermissionDao;
import stroom.security.impl.TestModule;
import stroom.security.impl.UserDao;
import stroom.security.shared.DocumentPermissionNames;
import stroom.security.shared.User;
import stroom.util.AuditUtil;

import com.google.inject.Guice;
import com.google.inject.Injector;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TestDocPermissionDaoImpl {

    private static final String PERMISSION_READ = "READ";
    private static final String PERMISSION_USE = "USE";
    private static final String PERMISSION_UPDATE = "UPDATE";

    @Inject
    private UserDao userDao;
    @Inject
    private DocumentPermissionDao documentPermissionDao;
    @Inject
    private SecurityDbConnProvider securityDbConnProvider;

    @BeforeEach
    void beforeEach() {
        final Injector injector = Guice.createInjector(
                new SecurityDbModule(),
                new SecurityDaoModule(),
                new TestModule());

        injector.injectMembers(this);
    }

    @AfterEach
    void tearDown() {
        SecurityTestUtil.teardown(securityDbConnProvider);
    }

    @Test
    void testAddPermission() {
        // Given
        final String userUuid = UUID.randomUUID().toString();
        final DocRef docRef = createTestDocRef();

        // When
        documentPermissionDao.addPermission(docRef.getUuid(), userUuid, "USE");
//        assertThrows(SecurityException.class,
//        () -> documentPermissionDao.addPermission(docRef.getUuid(), userUuid, "USE"));
    }

    private User createUser(final String name) {
        User user = User.builder()
                .subjectId(name)
                .uuid(UUID.randomUUID().toString())
                .build();
        AuditUtil.stamp(() -> "test", user);
        return userDao.create(user);
    }

    @Test
    void testDocPermissions() {
        final String userName1 = String.format("SomePerson_1_%s", UUID.randomUUID());
        final String userName2 = String.format("SomePerson_2_%s", UUID.randomUUID());
        final String userName3 = String.format("SomePerson_3_%s", UUID.randomUUID());
        final DocRef docRef1 = createTestDocRef();
        final DocRef docRef2 = createTestDocRef();

        final User user1 = createUser(userName1);
        final User user2 = createUser(userName2);
        final User user3 = createUser(userName3);

        // Create permissions for multiple documents to check that document selection is working correctly
        Stream.of(docRef1, docRef2)
                .map(DocRef::getUuid)
                .forEach(docRefUuid -> {
                    documentPermissionDao.addPermission(docRefUuid, user1.getUuid(), PERMISSION_READ);
                    documentPermissionDao.addPermission(docRefUuid, user1.getUuid(), PERMISSION_USE);
                    documentPermissionDao.addPermission(docRefUuid, user2.getUuid(), PERMISSION_USE);
                    documentPermissionDao.addPermission(docRefUuid, user3.getUuid(), PERMISSION_USE);
                    documentPermissionDao.addPermission(docRefUuid, user3.getUuid(), PERMISSION_UPDATE);
                });

        // Get the permissions for all users to this document
        final Map<String, Set<String>> permissionsFound1 = documentPermissionDao.getPermissionsForDocument(
                docRef1.getUuid()).getPermissionsMap();
//        assertThat(permissionsFound1.getDocUuid()).isEqualTo(docRef1.getUuid());

        final Set<String> permissionsFound1_user1 = permissionsFound1.get(user1.getUuid());
        assertThat(permissionsFound1_user1).isEqualTo(Set.of(PERMISSION_READ, PERMISSION_USE));

        final Set<String> permissionsFound1_user2 = permissionsFound1.get(user2.getUuid());
        assertThat(permissionsFound1_user2).isEqualTo(Set.of(PERMISSION_USE));

        final Set<String> permissionsFound1_user3 = permissionsFound1.get(user3.getUuid());
        assertThat(permissionsFound1_user3).isEqualTo(Set.of(PERMISSION_USE, PERMISSION_UPDATE));

        // Check permissions per user per document
        final Set<String> permissionsUser3Doc2_1 = documentPermissionDao.getPermissionsForDocumentForUser(
                docRef2.getUuid(),
                user3.getUuid());
        assertThat(permissionsUser3Doc2_1).isEqualTo(Set.of(PERMISSION_USE, PERMISSION_UPDATE));

        // Remove a couple of permission from docRef1 (leaving docRef2 in place)
        documentPermissionDao.removePermission(docRef1.getUuid(), user1.getUuid(), PERMISSION_READ);
        documentPermissionDao.removePermission(docRef1.getUuid(), user3.getUuid(), PERMISSION_UPDATE);

        // Get the permissions for all users to this document again
        final Map<String, Set<String>> permissionsFound2 = documentPermissionDao.getPermissionsForDocument(
                docRef1.getUuid()).getPermissionsMap();
//        assertThat(permissionsFound2.getDocUuid()).isEqualTo(docRef1.getUuid());

        final Set<String> permissionsFound2_user1 = permissionsFound2.get(user1.getUuid());
        assertThat(permissionsFound2_user1).isEqualTo(Set.of(PERMISSION_USE));

        final Set<String> permissionsFound2_user2 = permissionsFound2.get(user2.getUuid());
        assertThat(permissionsFound2_user2).isEqualTo(Set.of(PERMISSION_USE));

        final Set<String> permissionsFound2_user3 = permissionsFound2.get(user3.getUuid());
        assertThat(permissionsFound2_user3).isEqualTo(Set.of(PERMISSION_USE));

        // Check permissions per user per document
        final Set<String> permissionsUser3Doc2_2 = documentPermissionDao.getPermissionsForDocumentForUser(
                docRef2.getUuid(),
                user3.getUuid());
        assertThat(permissionsUser3Doc2_2).isEqualTo(Set.of(PERMISSION_USE, PERMISSION_UPDATE));

        final Set<String> permissionsUser3Doc1_2 = documentPermissionDao.getPermissionsForDocumentForUser(
                docRef1.getUuid(),
                user3.getUuid());
        assertThat(permissionsUser3Doc1_2).isEqualTo(Set.of(PERMISSION_USE));
    }

    @Test
    void testDocPermissions2() {
        final String userName1 = String.format("SomePerson_1_%s", UUID.randomUUID());
        final String userName2 = String.format("SomePerson_2_%s", UUID.randomUUID());
        final String userName3 = String.format("SomePerson_3_%s", UUID.randomUUID());
        final DocRef docRef1 = createTestDocRef();
        final DocRef docRef2 = createTestDocRef();

        final User user1 = createUser(userName1);
        final User user2 = createUser(userName2);
        final User user3 = createUser(userName3);

        // Create permissions for multiple documents to check that document selection is working correctly
        Stream.of(docRef1, docRef2)
                .map(DocRef::getUuid)
                .forEach(docRefUuid -> {
                    documentPermissionDao.addPermission(docRefUuid, user1.getUuid(), PERMISSION_READ);
                    documentPermissionDao.addPermission(docRefUuid, user1.getUuid(), PERMISSION_USE);
                    documentPermissionDao.addPermission(docRefUuid, user2.getUuid(), PERMISSION_USE);
                    documentPermissionDao.addPermission(docRefUuid, user3.getUuid(), PERMISSION_USE);
                    documentPermissionDao.addPermission(docRefUuid, user3.getUuid(), PERMISSION_UPDATE);
                });

        // Get the permissions for all users to this document
        final Map<String, Set<String>> permissionsFound1 = documentPermissionDao.getPermissionsForDocument(
                docRef1.getUuid()).getPermissionsMap();
//        assertThat(permissionsFound1.getDocUuid()).isEqualTo(docRef1.getUuid());

        final Set<String> permissionsFound1_user1 = permissionsFound1.get(user1.getUuid());
        assertThat(permissionsFound1_user1).isEqualTo(Set.of(PERMISSION_READ, PERMISSION_USE));

        final Set<String> permissionsFound1_user2 = permissionsFound1.get(user2.getUuid());
        assertThat(permissionsFound1_user2).isEqualTo(Set.of(PERMISSION_USE));

        final Set<String> permissionsFound1_user3 = permissionsFound1.get(user3.getUuid());
        assertThat(permissionsFound1_user3).isEqualTo(Set.of(PERMISSION_USE, PERMISSION_UPDATE));

        // Check permissions per user per document
        final Set<String> permissionsUser3Doc2_1 = documentPermissionDao.getPermissionsForDocumentForUser(
                docRef2.getUuid(),
                user3.getUuid());
        assertThat(permissionsUser3Doc2_1).isEqualTo(Set.of(PERMISSION_USE, PERMISSION_UPDATE));

        // Remove a couple of permission from docRef1 (leaving docRef2 in place)
        documentPermissionDao.removePermission(docRef1.getUuid(), user1.getUuid(), PERMISSION_READ);
        documentPermissionDao.removePermission(docRef1.getUuid(), user3.getUuid(), PERMISSION_UPDATE);

        // Get the permissions for all users to this document again
        final Map<String, BasicDocPermissions> allPermissions = documentPermissionDao.getPermissionsForDocuments(
                List.of(docRef1.getUuid(), docRef2.getUuid()));
        assertThat(allPermissions)
                .hasSize(2);
        assertThat(allPermissions.keySet())
                .containsExactlyInAnyOrder(docRef1.getUuid(), docRef2.getUuid());
        final Map<String, Set<String>> permissionsFound2 = allPermissions.get(docRef1.getUuid()).getPermissionsMap();

        final Set<String> permissionsFound2_user1 = permissionsFound2.get(user1.getUuid());
        assertThat(permissionsFound2_user1).isEqualTo(Set.of(PERMISSION_USE));

        final Set<String> permissionsFound2_user2 = permissionsFound2.get(user2.getUuid());
        assertThat(permissionsFound2_user2).isEqualTo(Set.of(PERMISSION_USE));

        final Set<String> permissionsFound2_user3 = permissionsFound2.get(user3.getUuid());
        assertThat(permissionsFound2_user3).isEqualTo(Set.of(PERMISSION_USE));

        // Check permissions per user per document
        final Set<String> permissionsUser3Doc2_2 = documentPermissionDao.getPermissionsForDocumentForUser(
                docRef2.getUuid(),
                user3.getUuid());
        assertThat(permissionsUser3Doc2_2).isEqualTo(Set.of(PERMISSION_USE, PERMISSION_UPDATE));

        final Set<String> permissionsUser3Doc1_2 = documentPermissionDao.getPermissionsForDocumentForUser(
                docRef1.getUuid(),
                user3.getUuid());
        assertThat(permissionsUser3Doc1_2).isEqualTo(Set.of(PERMISSION_USE));
    }

    @Test
    void testClearDocumentPermissions() {
        // Given
        final String userName1 = String.format("SomePerson_1_%s", UUID.randomUUID());
        final DocRef docRef1 = createTestDocRef();
        final DocRef docRef2 = createTestDocRef();

        final User user1 = createUser(userName1);

        // Create permissions for multiple documents to check that document selection is working correctly
        Stream.of(docRef1, docRef2)
                .map(DocRef::getUuid)
                .forEach(docRefUuid -> {
                    documentPermissionDao.addPermission(docRefUuid, user1.getUuid(), PERMISSION_READ);
                    documentPermissionDao.addPermission(docRefUuid, user1.getUuid(), PERMISSION_USE);
                });

        Stream.of(docRef1, docRef2).forEach(d -> {
            final Set<String> permissionsBefore = documentPermissionDao.getPermissionsForDocumentForUser(
                    docRef1.getUuid(),
                    user1.getUuid());
            assertThat(permissionsBefore).isEqualTo(Set.of(PERMISSION_READ, PERMISSION_USE));
        });

        // When
        documentPermissionDao.clearDocumentPermissions(docRef1.getUuid());

        // Then
        // The two documents will now have different permissions
        final Set<String> user1Doc1After = documentPermissionDao.getPermissionsForDocumentForUser(
                docRef1.getUuid(),
                user1.getUuid());
        assertThat(user1Doc1After).isEmpty();

        final Set<String> user2Doc1After = documentPermissionDao.getPermissionsForDocumentForUser(
                docRef2.getUuid(),
                user1.getUuid());
        assertThat(user2Doc1After).isEqualTo(Set.of(PERMISSION_READ, PERMISSION_USE));
    }

    @Test
    void testSetOwner() {
        // Given
        final String userName1 = String.format("SomePerson_1_%s", UUID.randomUUID());
        final String userName2 = String.format("SomePerson_2_%s", UUID.randomUUID());
        final DocRef docRef1 = createTestDocRef();
        final DocRef docRef2 = createTestDocRef();
        final List<DocRef> docRefs = List.of(docRef1, docRef2);

        final User user1 = createUser(userName1);
        final User user2 = createUser(userName2);
        final List<User> users = List.of(user1, user2);

        // Make each user the owner of both docs, i.e. each doc has two owners
        for (final User user : users) {
            for (final DocRef docRef : docRefs) {
                documentPermissionDao.addPermission(docRef.getUuid(), user.getUuid(), DocumentPermissionNames.OWNER);
            }
        }

        for (final User user : users) {
            for (final DocRef docRef : docRefs) {
                final Set<String> permissionsBefore = documentPermissionDao.getPermissionsForDocumentForUser(
                        docRef.getUuid(),
                        user.getUuid());
                assertThat(permissionsBefore)
                        .containsExactly(DocumentPermissionNames.OWNER);
            }
        }

        // When
        final String userName3 = String.format("SomePerson_3_%s", UUID.randomUUID());
        final User user3 = createUser(userName3);
        documentPermissionDao.setOwner(docRef1.getUuid(), user3.getUuid());

        assertThat(documentPermissionDao.getDocumentOwnerUuids(docRef1.getUuid()))
                .containsExactly(user3.getUuid());
        // docRef2 unchanged
        assertThat(documentPermissionDao.getDocumentOwnerUuids(docRef2.getUuid()))
                .containsExactlyInAnyOrder(user1.getUuid(), user2.getUuid());
    }

    private DocRef createTestDocRef() {
        return DocRef.builder()
                .type("Simple")
                .uuid(UUID.randomUUID().toString())
                .build();
    }
}
