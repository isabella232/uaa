package org.cloudfoundry.identity.uaa.scim.endpoints;

import org.cloudfoundry.identity.uaa.constants.OriginKeys;
import org.cloudfoundry.identity.uaa.provider.JdbcIdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.resources.SearchResults;
import org.cloudfoundry.identity.uaa.resources.jdbc.JdbcPagingListFactory;
import org.cloudfoundry.identity.uaa.resources.jdbc.LimitSqlAdapterFactory;
import org.cloudfoundry.identity.uaa.scim.*;
import org.cloudfoundry.identity.uaa.scim.bootstrap.ScimExternalGroupBootstrap;
import org.cloudfoundry.identity.uaa.scim.exception.InvalidScimResourceException;
import org.cloudfoundry.identity.uaa.scim.exception.ScimException;
import org.cloudfoundry.identity.uaa.scim.exception.ScimResourceAlreadyExistsException;
import org.cloudfoundry.identity.uaa.scim.exception.ScimResourceNotFoundException;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupExternalMembershipManager;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupMembershipManager;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupProvisioning;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.test.TestUtils;
import org.cloudfoundry.identity.uaa.scim.validate.PasswordValidator;
import org.cloudfoundry.identity.uaa.test.JdbcTestBase;
import org.cloudfoundry.identity.uaa.util.FakePasswordEncoder;
import org.cloudfoundry.identity.uaa.web.ExceptionReportHttpMessageConverter;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.MultitenancyFixture;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManagerImpl;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.servlet.View;

import java.util.*;

import static java.util.Collections.emptyList;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ScimGroupEndpointsTests extends JdbcTestBase {

    private JdbcScimGroupProvisioning jdbcScimGroupProvisioning;

    private JdbcScimGroupMembershipManager jdbcScimGroupMembershipManager;

    private JdbcScimGroupExternalMembershipManager jdbcScimGroupExternalMembershipManager;

    private ScimGroupEndpoints scimGroupEndpoints;

    private ScimUserEndpoints scimUserEndpoints;

    private List<String> groupIds;

    private List<String> userIds;

    private static final String SQL_INJECTION_FIELDS = "displayName,version,created,lastModified";

    @Rule
    public ExpectedException expectedEx = ExpectedException.none();

    @Before
    public void initScimGroupEndpointsTests() throws Exception {
        TestUtils.deleteFrom(jdbcTemplate, "users", "groups", "group_membership");
        JdbcTemplate template = jdbcTemplate;
        JdbcPagingListFactory pagingListFactory = new JdbcPagingListFactory(template, LimitSqlAdapterFactory.getLimitSqlAdapter());
        jdbcScimGroupProvisioning = new JdbcScimGroupProvisioning(template, pagingListFactory);
        JdbcScimUserProvisioning udao = new JdbcScimUserProvisioning(template, pagingListFactory, new FakePasswordEncoder());
        jdbcScimGroupMembershipManager = new JdbcScimGroupMembershipManager(template);
        jdbcScimGroupMembershipManager.setScimGroupProvisioning(jdbcScimGroupProvisioning);
        jdbcScimGroupMembershipManager.setScimUserProvisioning(udao);
        IdentityZoneHolder.get().getConfig().getUserConfig().setDefaultGroups(Collections.singletonList("uaa.user"));
        jdbcScimGroupProvisioning.createOrGet(new ScimGroup(null, "uaa.user", IdentityZoneHolder.get().getId()), IdentityZoneHolder.get().getId());

        jdbcScimGroupExternalMembershipManager = new JdbcScimGroupExternalMembershipManager(template);
        jdbcScimGroupExternalMembershipManager.setScimGroupProvisioning(jdbcScimGroupProvisioning);

        scimGroupEndpoints = new ScimGroupEndpoints(jdbcScimGroupProvisioning, jdbcScimGroupMembershipManager);
        scimGroupEndpoints.setExternalMembershipManager(jdbcScimGroupExternalMembershipManager);
        scimGroupEndpoints.setGroupMaxCount(20);

        scimUserEndpoints = new ScimUserEndpoints(new IdentityZoneManagerImpl());
        scimUserEndpoints.setUserMaxCount(5);
        scimUserEndpoints.setScimUserProvisioning(udao);
        scimUserEndpoints.setIdentityProviderProvisioning(mock(JdbcIdentityProviderProvisioning.class));
        scimUserEndpoints.setScimGroupMembershipManager(jdbcScimGroupMembershipManager);
        scimUserEndpoints.setPasswordValidator(mock(PasswordValidator.class));

        groupIds = new ArrayList<>();
        userIds = new ArrayList<>();
        groupIds.add(addGroup("uaa.resource",
                Arrays.asList(createMember(ScimGroupMember.Type.USER),
                        createMember(ScimGroupMember.Type.GROUP),
                        createMember(ScimGroupMember.Type.USER)))
        );
        groupIds.add(addGroup("uaa.admin", Collections.emptyList()));
        groupIds.add(addGroup("uaa.none",
                Arrays.asList(createMember(ScimGroupMember.Type.USER),
                        createMember(ScimGroupMember.Type.GROUP)))
        );

        ScimExternalGroupBootstrap externalGroupBootstrap = new ScimExternalGroupBootstrap(jdbcScimGroupProvisioning, jdbcScimGroupExternalMembershipManager);
        externalGroupBootstrap.setAddNonExistingGroups(true);

        Map<String, Map<String, List>> externalGroups = new HashMap<>();
        Map<String, List> externalToInternalMap = new HashMap<>();
        externalToInternalMap.put("cn=test_org,ou=people,o=springsource,o=org", Collections.singletonList("organizations.acme"));
        externalToInternalMap.put("cn=developers,ou=scopes,dc=test,dc=com", Collections.singletonList("internal.read"));
        externalToInternalMap.put("cn=operators,ou=scopes,dc=test,dc=com", Collections.singletonList("internal.write"));
        externalToInternalMap.put("cn=superusers,ou=scopes,dc=test,dc=com", Arrays.asList("internal.everything", "internal.superuser"));
        externalGroups.put(OriginKeys.LDAP, externalToInternalMap);
        externalGroups.put("other-ldap", externalToInternalMap);
        externalGroupBootstrap.setExternalGroupMaps(externalGroups);
        externalGroupBootstrap.afterPropertiesSet();
    }

    private String addGroup(String name, List<ScimGroupMember> m) {
        ScimGroup g = new ScimGroup(null, name, IdentityZoneHolder.get().getId());
        g = jdbcScimGroupProvisioning.create(g, IdentityZoneHolder.get().getId());
        for (ScimGroupMember member : m) {
            jdbcScimGroupMembershipManager.addMember(g.getId(), member, IdentityZoneHolder.get().getId());
        }
        return g.getId();
    }

    private ScimGroupMember createMember(ScimGroupMember.Type t) {
        String id = UUID.randomUUID().toString();
        if (t == ScimGroupMember.Type.USER) {
            id = scimUserEndpoints.createUser(TestUtils.scimUserInstance(id), new MockHttpServletRequest(), new MockHttpServletResponse()).getId();
            userIds.add(id);
        } else {
            id = jdbcScimGroupProvisioning.create(new ScimGroup(null, id, IdentityZoneHolder.get().getId()), IdentityZoneHolder.get().getId()).getId();
            groupIds.add(id);
        }
        return new ScimGroupMember(id, t);
    }

    private void deleteGroup(String name) {
        for (ScimGroup g : jdbcScimGroupProvisioning.query("displayName eq \"" + name + "\"", IdentityZoneHolder.get().getId())) {
            jdbcScimGroupProvisioning.delete(g.getId(), g.getVersion(), IdentityZoneHolder.get().getId());
            jdbcScimGroupMembershipManager.removeMembersByGroupId(g.getId(), IdentityZoneHolder.get().getId());
        }
    }

    private void validateSearchResults(SearchResults<?> results, int expectedSize) {
        assertNotNull(results);
        assertNotNull(results.getResources());
        assertEquals(expectedSize, results.getResources().size());
    }

    private void validateGroup(ScimGroup g, String expectedName, int expectedMemberCount) {
        assertNotNull(g);
        assertNotNull(g.getId());
        assertEquals(expectedName, g.getDisplayName());
        assertNotNull(g.getMembers());
        assertEquals(expectedMemberCount, g.getMembers().size());
    }

    private void validateUserGroups(String id, String... gnm) {
        ScimUser user = scimUserEndpoints.getUser(id, new MockHttpServletResponse());
        Set<String> expectedAuthorities = new HashSet<>(Arrays.asList(gnm));
        expectedAuthorities.add("uaa.user");
        assertNotNull(user.getGroups());
        assertEquals(expectedAuthorities.size(), user.getGroups().size());
        for (ScimUser.Group g : user.getGroups()) {
            assertTrue(expectedAuthorities.contains(g.getDisplay()));
        }
    }

    @Test
    public void testListGroups() {
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayName", "id pr", "created", "ascending", 1, 100), 11);
    }

    @Test
    public void testListGroupsWithAttributesWithoutMembersDoesNotQueryMembers() {
        ScimGroupMembershipManager memberManager = mock(ScimGroupMembershipManager.class);
        scimGroupEndpoints = new ScimGroupEndpoints(jdbcScimGroupProvisioning, memberManager);
        scimGroupEndpoints.setExternalMembershipManager(jdbcScimGroupExternalMembershipManager);
        scimGroupEndpoints.setGroupMaxCount(20);
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayName", "id pr", "created", "ascending", 1, 100), 11);
        verify(memberManager, times(0)).getMembers(anyString(), any(Boolean.class), anyString());
    }

    @Test
    public void testListGroupsWithAttributesWithMembersDoesQueryMembers() {
        ScimGroupMembershipManager memberManager = mock(ScimGroupMembershipManager.class);
        when(memberManager.getMembers(anyString(), eq(false), eq("uaa"))).thenReturn(Collections.emptyList());
        scimGroupEndpoints = new ScimGroupEndpoints(jdbcScimGroupProvisioning, memberManager);
        scimGroupEndpoints.setExternalMembershipManager(jdbcScimGroupExternalMembershipManager);
        scimGroupEndpoints.setGroupMaxCount(20);
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayName,members", "id pr", "created", "ascending", 1, 100), 11);
        verify(memberManager, atLeastOnce()).getMembers(anyString(), any(Boolean.class), anyString());
    }

    @Test
    public void whenSettingAnInvalidGroupsMaxCount_ScimGroupsEndpointShouldThrowAnException() {
        expectedEx.expect(IllegalArgumentException.class);
        expectedEx.expectMessage(containsString(
                "Invalid \"groupMaxCount\" value (got 0). Should be positive number."
        ));
        scimGroupEndpoints.setGroupMaxCount(0);
    }

    @Test
    public void whenSettingANegativeValueGroupsMaxCount_ScimGroupsEndpointShouldThrowAnException() {
        expectedEx.expect(IllegalArgumentException.class);
        expectedEx.expectMessage(containsString(
                "Invalid \"groupMaxCount\" value (got -1). Should be positive number."
        ));
        scimGroupEndpoints.setGroupMaxCount(-1);
    }

    @Test
    public void testListGroups_Without_Description() {
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayName,description", "id pr", "created", "ascending", 1, 100), 11);
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayName,meta.lastModified", "id pr", "created", "ascending", 1, 100), 11);
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayName,zoneId", "id pr", "created", "ascending", 1, 100), 11);
    }


    @Test
    public void testListExternalGroups() {
        validateSearchResults(scimGroupEndpoints.getExternalGroups(1, 100, "", "", ""), 10);

        validateSearchResults(scimGroupEndpoints.getExternalGroups(1, 100, "", OriginKeys.LDAP, ""), 5);
        validateSearchResults(scimGroupEndpoints.getExternalGroups(1, 100, "", "", "cn=superusers,ou=scopes,dc=test,dc=com"), 4);
        validateSearchResults(scimGroupEndpoints.getExternalGroups(1, 100, "", OriginKeys.LDAP, "cn=superusers,ou=scopes,dc=test,dc=com"), 2);
        validateSearchResults(scimGroupEndpoints.getExternalGroups(1, 100, "", "you-wont-find-me", "cn=superusers,ou=scopes,dc=test,dc=com"), 0);

        validateSearchResults(scimGroupEndpoints.getExternalGroups(1, 100, "externalGroup eq \"cn=superusers,ou=scopes,dc=test,dc=com\"", "", ""), 4);
        validateSearchResults(scimGroupEndpoints.getExternalGroups(1, 100, "origin eq \"" + OriginKeys.LDAP + "\"", "", ""), 5);
        validateSearchResults(scimGroupEndpoints.getExternalGroups(1, 100, "externalGroup eq \"cn=superusers,ou=scopes,dc=test,dc=com\" and " + "origin eq \"" + OriginKeys.LDAP + "\"", "", ""), 2);
    }

    @Test
    public void testListExternalGroupsInvalidFilter() {
        for (String filter : Arrays.asList(
                "dasda dasdas dasdas",
                "displayName eq \"test\""
        ))
            try {
                scimGroupEndpoints.getExternalGroups(1, 100, filter, null, null);
                fail("Filter: " + filter);
            } catch (ScimException x) {
                //expected
            }
    }


    @Test
    public void mapExternalGroup_truncatesLeadingAndTrailingSpaces_InExternalGroupName() {
        ScimGroupExternalMember member = getScimGroupExternalMember();
        assertEquals("external_group_id", member.getExternalGroup());
    }

    @Test
    public void unmapExternalGroup_truncatesLeadingAndTrailingSpaces_InExternalGroupName() {
        ScimGroupExternalMember member = getScimGroupExternalMember();
        member = scimGroupEndpoints.unmapExternalGroup(member.getGroupId(), "  \nexternal_group_id\n", OriginKeys.LDAP);
        assertEquals("external_group_id", member.getExternalGroup());
    }

    @Test
    public void unmapExternalGroupUsingName_truncatesLeadingAndTrailingSpaces_InExternalGroupName() {
        ScimGroupExternalMember member = getScimGroupExternalMember();
        member = scimGroupEndpoints.unmapExternalGroupUsingName(member.getDisplayName(), "  \nexternal_group_id\n");
        assertEquals("external_group_id", member.getExternalGroup());
    }

    private ScimGroupExternalMember getScimGroupExternalMember() {
        ScimGroupExternalMember member = new ScimGroupExternalMember(groupIds.get(0), "  external_group_id  ");
        member = scimGroupEndpoints.mapExternalGroup(member);
        return member;
    }

    @Test
    public void testFindPageOfIds() {
        SearchResults<?> results = scimGroupEndpoints.listGroups("id", "id pr", null, "ascending", 1, 1);
        assertEquals(11, results.getTotalResults());
        assertEquals(1, results.getResources().size());
    }

    @Test
    public void testFindMultiplePagesOfIds() {
        int pageSize = jdbcScimGroupProvisioning.getPageSize();
        jdbcScimGroupProvisioning.setPageSize(1);
        try {
            SearchResults<?> results = scimGroupEndpoints.listGroups("id", "id pr", null, "ascending", 1, 100);
            assertEquals(11, results.getTotalResults());
            assertEquals(11, results.getResources().size());
        } finally {
            jdbcScimGroupProvisioning.setPageSize(pageSize);
        }
    }

    @Test
    public void testListGroupsWithNameEqFilter() {
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayName", "displayName eq \"uaa.user\"", "created",
                "ascending", 1, 100), 1);
    }

    @Test
    public void testListGroupsWithNameCoFilter() {
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayName", "displayName co \"admin\"", "created", "ascending",
                1, 100), 1);
    }

    @Test
    public void testListGroupsWithInvalidFilterFails() {
        expectedEx.expect(ScimException.class);
        expectedEx.expectMessage("Invalid filter expression");
        scimGroupEndpoints.listGroups("id,displayName", "displayName cr \"admin\"", "created", "ascending", 1, 100);
    }

    @Test
    public void testListGroupsWithInvalidAttributes() {
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayNameee", "displayName co \"admin\"", "created", "ascending", 1, 100), 1);
    }

    @Test
    public void testListGroupsWithNullAttributes() {
        validateSearchResults(scimGroupEndpoints.listGroups(null, "displayName co \"admin\"", "created", "ascending", 1, 100), 1);
    }

    @Test
    public void testSqlInjectionAttackFailsCorrectly() {
        expectedEx.expect(ScimException.class);
        expectedEx.expectMessage("Invalid filter expression");
        scimGroupEndpoints.listGroups("id,display", "displayName='something'; select " + SQL_INJECTION_FIELDS
                + " from groups where displayName='something'", "created", "ascending", 1, 100);
    }

    @Test
    public void legacyTestListGroupsWithNameEqFilter() {
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayName", "displayName eq 'uaa.user'", "created",
                "ascending", 1, 100), 1);
    }

    @Test
    public void legacyTestListGroupsWithNameCoFilter() {
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayName", "displayName co 'admin'", "created", "ascending",
                1, 100), 1);
    }

    @Test
    public void legacyTestListGroupsWithInvalidFilterFails() {
        expectedEx.expect(ScimException.class);
        expectedEx.expectMessage("Invalid filter expression");
        scimGroupEndpoints.listGroups("id,displayName", "displayName cr 'admin'", "created", "ascending", 1, 100);
    }

    @Test
    public void legacyTestListGroupsWithInvalidAttributes() {
        validateSearchResults(scimGroupEndpoints.listGroups("id,displayNameee", "displayName co 'admin'", "created", "ascending", 1, 100), 1);
    }

    @Test
    public void legacyTestListGroupsWithNullAttributes() {
        validateSearchResults(scimGroupEndpoints.listGroups(null, "displayName co 'admin'", "created", "ascending", 1, 100), 1);
    }

    @Test
    public void testGetGroup() {
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        ScimGroup g = scimGroupEndpoints.getGroup(groupIds.get(groupIds.size() - 1), httpServletResponse);
        validateGroup(g, "uaa.none", 2);
        assertEquals("\"0\"", httpServletResponse.getHeader("ETag"));
    }

    @Test
    public void testGetNonExistentGroupFails() {
        expectedEx.expect(ScimResourceNotFoundException.class);
        scimGroupEndpoints.getGroup("wrongid", new MockHttpServletResponse());
    }

    @Test
    public void testCreateGroup() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        ScimGroup g1 = scimGroupEndpoints.createGroup(g, httpServletResponse);
        assertEquals("\"0\"", httpServletResponse.getHeader("ETag"));

        validateGroup(g1, "clients.read", 1);
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        deleteGroup("clients.read");
    }

    @Test
    public void testCreateExistingGroupFails() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
        try {
            scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
            fail("must have thrown exception");
        } catch (ScimResourceAlreadyExistsException ex) {
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testCreateGroupWithInvalidMemberFails() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(new ScimGroupMember("non-existent id", ScimGroupMember.Type.USER)));

        try {
            scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
            fail("must have thrown exception");
        } catch (InvalidScimResourceException ex) {
            // ensure that the group was not created
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 0);
        }
    }

    @Test
    public void testUpdateGroup() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g = scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        ScimGroup g1 = scimGroupEndpoints.updateGroup(g, g.getId(), "*", httpServletResponse);
        assertEquals("\"1\"", httpServletResponse.getHeader("ETag"));

        validateGroup(g1, "superadmin", 1);
        validateUserGroups(g.getMembers().get(0).getMemberId(), "superadmin");
    }

    @Test
    public void testUpdateGroupQuotedEtag() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g = scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        ScimGroup g1 = scimGroupEndpoints.updateGroup(g, g.getId(), "\"*\"", httpServletResponse);
        assertEquals("\"1\"", httpServletResponse.getHeader("ETag"));

        validateGroup(g1, "superadmin", 1);
        validateUserGroups(g.getMembers().get(0).getMemberId(), "superadmin");
    }

    @Test
    public void testUpdateGroupRemoveMembers() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g = scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        g.setMembers(new ArrayList<>());
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        ScimGroup g1 = scimGroupEndpoints.updateGroup(g, g.getId(), "*", httpServletResponse);
        assertEquals("\"1\"", httpServletResponse.getHeader("ETag"));

        validateGroup(g1, "superadmin", 0);
    }

    @Test(expected = ScimException.class)
    public void testUpdateGroupNullEtag() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g = scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        scimGroupEndpoints.updateGroup(g, g.getId(), null, httpServletResponse);
    }

    @Test(expected = ScimException.class)
    public void testUpdateGroupNoEtag() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g = scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        scimGroupEndpoints.updateGroup(g, g.getId(), "", httpServletResponse);
    }

    @Test(expected = ScimException.class)
    public void testUpdateGroupInvalidEtag() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g = scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        scimGroupEndpoints.updateGroup(g, g.getId(), "abc", httpServletResponse);
    }

    @Test
    public void testUpdateNonUniqueDisplayNameFails() {
        ScimGroup g1 = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g1.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g1 = scimGroupEndpoints.createGroup(g1, new MockHttpServletResponse());

        ScimGroup g2 = new ScimGroup(null, "clients.write", IdentityZoneHolder.get().getId());
        g2.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g2 = scimGroupEndpoints.createGroup(g2, new MockHttpServletResponse());

        g1.setDisplayName("clients.write");
        try {
            scimGroupEndpoints.updateGroup(g1, g1.getId(), "*", new MockHttpServletResponse());
            fail("must have thrown exception");
        } catch (InvalidScimResourceException ex) {
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 1);
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
        deleteGroup("clients.write");
    }

    @Test
    public void testUpdateWithInvalidMemberFails() {
        ScimGroup g1 = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g1.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g1 = scimGroupEndpoints.createGroup(g1, new MockHttpServletResponse());

        g1.setMembers(
                Collections.singletonList(
                        new ScimGroupMember("non-existent id", ScimGroupMember.Type.USER)
                )
        );
        g1.setDisplayName("clients.write");

        try {
            scimGroupEndpoints.updateGroup(g1, g1.getId(), "*", new MockHttpServletResponse());
            fail("must have thrown exception");
        } catch (ScimException ex) {
            // ensure that displayName was not updated
            g1 = scimGroupEndpoints.getGroup(g1.getId(), new MockHttpServletResponse());
            validateGroup(g1, "clients.read", 0);
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 0);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testUpdateInvalidVersionFails() {
        ScimGroup g1 = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g1.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g1 = scimGroupEndpoints.createGroup(g1, new MockHttpServletResponse());

        g1.setDisplayName("clients.write");

        try {
            scimGroupEndpoints.updateGroup(g1, g1.getId(), "version", new MockHttpServletResponse());
        } catch (ScimException ex) {
            assertTrue("Wrong exception message", ex.getMessage().contains("Invalid version"));
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 0);
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testUpdateGroupWithNullEtagFails() {
        ScimGroup g1 = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g1.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g1 = scimGroupEndpoints.createGroup(g1, new MockHttpServletResponse());

        g1.setDisplayName("clients.write");

        try {
            scimGroupEndpoints.updateGroup(g1, g1.getId(), null, new MockHttpServletResponse());
        } catch (ScimException ex) {
            assertTrue("Wrong exception message", ex.getMessage().contains("Missing If-Match"));
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 0);
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testUpdateWithQuotedVersionSucceeds() {
        ScimGroup g1 = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g1.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g1 = scimGroupEndpoints.createGroup(g1, new MockHttpServletResponse());

        g1.setDisplayName("clients.write");

        scimGroupEndpoints.updateGroup(g1, g1.getId(), "\"*", new MockHttpServletResponse());
        scimGroupEndpoints.updateGroup(g1, g1.getId(), "*\"", new MockHttpServletResponse());
        validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 1);
        validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 0);

        deleteGroup("clients.write");
    }

    @Test
    public void testUpdateWrongVersionFails() {
        ScimGroup g1 = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g1.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g1 = scimGroupEndpoints.createGroup(g1, new MockHttpServletResponse());

        g1.setDisplayName("clients.write");

        try {
            scimGroupEndpoints.updateGroup(g1, g1.getId(), String.valueOf(g1.getVersion() + 23), new MockHttpServletResponse());
        } catch (ScimException ex) {
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 0);
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testUpdateGroupWithNoMembers() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g = scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("someadmin");
        g.setMembers(null);
        ScimGroup g1 = scimGroupEndpoints.updateGroup(g, g.getId(), "*", new MockHttpServletResponse());
        validateGroup(g1, "someadmin", 0);

        deleteGroup("clients.read");
    }

    @Test
    public void testDeleteGroup() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g = scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g = scimGroupEndpoints.deleteGroup(g.getId(), "*", new MockHttpServletResponse());
        try {
            scimGroupEndpoints.getGroup(g.getId(), new MockHttpServletResponse());
            fail("group should not exist");
        } catch (ScimResourceNotFoundException ignored) {
        }
        validateUserGroups(g.getMembers().get(0).getMemberId(), "uaa.user");
    }

    @Test
    public void testDeleteGroupRemovesMembershipsInZone() {
        IdentityZone zone = MultitenancyFixture.identityZone("test-zone-id", "test");
        zone.getConfig().getUserConfig().setDefaultGroups(emptyList());
        IdentityZoneHolder.set(zone);

        ScimGroup group = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        ScimGroupMember member = createMember(ScimGroupMember.Type.GROUP);
        group.setMembers(Collections.singletonList(member));

        group = scimGroupEndpoints.createGroup(group, new MockHttpServletResponse());

        scimGroupEndpoints.deleteGroup(member.getMemberId(), "*", new MockHttpServletResponse());

        List<ScimGroupMember> members = scimGroupEndpoints.listGroupMemberships(group.getId(), true, "").getBody();
        assertEquals(0, members.size());
    }

    @Test
    public void testDeleteWrongVersionFails() {
        ScimGroup g = new ScimGroup(null, "clients.read", IdentityZoneHolder.get().getId());
        g.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        g = scimGroupEndpoints.createGroup(g, new MockHttpServletResponse());

        try {
            scimGroupEndpoints.deleteGroup(g.getId(), String.valueOf(g.getVersion() + 3), new MockHttpServletResponse());
        } catch (ScimException ex) {
            validateSearchResults(scimGroupEndpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testDeleteNonExistentGroupFails() {
        expectedEx.expect(ScimResourceNotFoundException.class);
        scimGroupEndpoints.deleteGroup("some id", "*", new MockHttpServletResponse());
    }

    @Test
    public void testExceptionHandler() {
        Map<Class<? extends Exception>, HttpStatus> map = new HashMap<>();
        map.put(IllegalArgumentException.class, HttpStatus.BAD_REQUEST);
        map.put(UnsupportedOperationException.class, HttpStatus.BAD_REQUEST);
        map.put(BadSqlGrammarException.class, HttpStatus.BAD_REQUEST);
        map.put(DataIntegrityViolationException.class, HttpStatus.BAD_REQUEST);
        map.put(HttpMessageConversionException.class, HttpStatus.BAD_REQUEST);
        map.put(HttpMediaTypeException.class, HttpStatus.BAD_REQUEST);
        scimGroupEndpoints.setStatuses(map);
        scimGroupEndpoints.setMessageConverters(new HttpMessageConverter<?>[]{new ExceptionReportHttpMessageConverter()});

        MockHttpServletRequest request = new MockHttpServletRequest();
        validateView(scimGroupEndpoints.handleException(new ScimResourceNotFoundException(""), request), HttpStatus.NOT_FOUND);
        validateView(scimGroupEndpoints.handleException(new UnsupportedOperationException(""), request), HttpStatus.BAD_REQUEST);
        validateView(scimGroupEndpoints.handleException(new BadSqlGrammarException("", "", null), request),
                HttpStatus.BAD_REQUEST);
        validateView(scimGroupEndpoints.handleException(new IllegalArgumentException(""), request), HttpStatus.BAD_REQUEST);
        validateView(scimGroupEndpoints.handleException(new DataIntegrityViolationException(""), request),
                HttpStatus.BAD_REQUEST);
    }

    private void validateView(View view, HttpStatus status) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            view.render(new HashMap<>(), new MockHttpServletRequest(), response);
            assertNotNull(response.getContentAsString());
        } catch (Exception e) {
            fail("view should render correct status and body");
        }
        assertEquals(status.value(), response.getStatus());
    }

    @Test
    public void testPatch() {
        ScimGroup g1 = new ScimGroup(null, "name", IdentityZoneHolder.get().getId());
        g1.setDescription("description");

        g1 = jdbcScimGroupProvisioning.create(g1, IdentityZoneHolder.get().getId());

        ScimGroup patch = new ScimGroup("NewName");
        patch.setId(g1.getId());

        patch = scimGroupEndpoints.patchGroup(patch, patch.getId(), Integer.toString(g1.getVersion()), new MockHttpServletResponse());

        assertEquals("NewName", patch.getDisplayName());
        assertEquals(g1.getDescription(), patch.getDescription());
    }

    @Test(expected = ScimException.class)
    public void testPatchInvalidResourceFails() {
        ScimGroup g1 = new ScimGroup(null, "name", IdentityZoneHolder.get().getId());
        g1.setDescription("description");

        scimGroupEndpoints.patchGroup(g1, "id", "0", new MockHttpServletResponse());
    }

    @Test
    public void testPatchAddMembers() {
        ScimGroup g1 = new ScimGroup(null, "name", IdentityZoneHolder.get().getId());
        g1.setDescription("description");

        g1 = jdbcScimGroupProvisioning.create(g1, IdentityZoneHolder.get().getId());

        ScimGroup patch = new ScimGroup();
        assertNull(g1.getMembers());
        assertNull(patch.getMembers());
        patch.setMembers(Collections.singletonList(createMember(ScimGroupMember.Type.USER)));
        assertEquals(1, patch.getMembers().size());

        patch = scimGroupEndpoints.patchGroup(patch, g1.getId(), "0", new MockHttpServletResponse());

        assertEquals(1, patch.getMembers().size());
        ScimGroupMember member = patch.getMembers().get(0);
        assertEquals(ScimGroupMember.Type.USER, member.getType());
    }

    @Test(expected = ScimException.class)
    public void testPatchIncorrectEtagFails() {
        ScimGroup g1 = new ScimGroup(null, "name", IdentityZoneHolder.get().getId());
        g1.setDescription("description");

        g1 = jdbcScimGroupProvisioning.create(g1, IdentityZoneHolder.get().getId());

        ScimGroup patch = new ScimGroup("NewName");
        patch.setId(g1.getId());

        scimGroupEndpoints.patchGroup(patch, patch.getId(), Integer.toString(g1.getVersion() + 1), new MockHttpServletResponse());
    }
}
