package jenkins.plugins.openstack.compute;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.Rule;
import org.junit.Test;
import org.openstack4j.model.compute.Address;
import org.openstack4j.model.compute.Addresses;
import org.openstack4j.model.compute.Server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import hudson.model.Node;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import jenkins.plugins.openstack.PluginTestRule;

public class JCloudsSlaveTest {
    private static final String EXPECTED_IP_ADDRESS_ENV_VAR_NAME = "OPENSTACK_PUBLIC_IP";
    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void constructorGivenNoNodePropertiesThenProvidesIPAddressAsEnvVar() throws Exception {
        // Given
        final String expectedIpAddress = "1.2.3.4";
        final ProvisioningActivity.Id stubId = new ProvisioningActivity.Id("id1");
        final String nodeName = "name1";
        final Server mockMetadata = mockServer(nodeName, expectedIpAddress);
        final String labelString = "";
        final SlaveOptions mockSlaveOptions = mock(SlaveOptions.class);
        when(mockSlaveOptions.getNumExecutors()).thenReturn(1);
        when(mockSlaveOptions.getNodeProperties()).thenReturn(null);
        final Map<String, String> expectedEnvVars = ImmutableMap.of(EXPECTED_IP_ADDRESS_ENV_VAR_NAME,
                expectedIpAddress);

        // When
        JCloudsSlave instance = new JCloudsSlave(stubId, mockMetadata, labelString, mockSlaveOptions);

        // Then
        final List<NodeProperty<?>> actualNPs = instance.getNodeProperties().toList();
        assertThat(actualNPs.size(), equalTo(1));
        final NodeProperty<?> actualNP = actualNPs.get(0);
        assertThat(actualNP, instanceOf(EnvironmentVariablesNodeProperty.class));
        final EnvironmentVariablesNodeProperty actualEnvVarNP = (EnvironmentVariablesNodeProperty) actualNP;
        final Map<String, String> actualEnvVars = ImmutableMap.copyOf(actualEnvVarNP.getEnvVars());
        assertThat(actualEnvVars, equalTo(expectedEnvVars));
    }

    @Test
    public void constructorGivenSomeNodePropertiesThenAddsIPAddressAsEnvVar() throws Exception {
        // Given
        final String expectedIpAddress = "2.3.4.5";
        final ProvisioningActivity.Id stubId = new ProvisioningActivity.Id("id2");
        final String nodeName = "name2";
        final Server mockMetadata = mockServer(nodeName, expectedIpAddress);
        final String labelString = "foo bar";
        final SlaveOptions mockSlaveOptions = mock(SlaveOptions.class);
        when(mockSlaveOptions.getNumExecutors()).thenReturn(1);
        final NodeProperty<Node> mockNP1 = mockNodeProperty();
        final NodeProperty<Node> mockNP2 = mockNodeProperty();
        final List<NodeProperty<?>> listOfMockNodeProperties = ImmutableList.of(mockNP1, mockNP2);
        when(mockSlaveOptions.getNodeProperties()).thenReturn(listOfMockNodeProperties);
        final Map<String, String> expectedEnvVars = ImmutableMap.of(EXPECTED_IP_ADDRESS_ENV_VAR_NAME,
                expectedIpAddress);

        // When
        JCloudsSlave instance = new JCloudsSlave(stubId, mockMetadata, labelString, mockSlaveOptions);

        // Then
        final List<NodeProperty<?>> actualNPs = instance.getNodeProperties().toList();
        assertThat(actualNPs.size(), equalTo(3));
        final NodeProperty<?> actualNP1 = actualNPs.get(0);
        assertThat(actualNP1, sameInstance(mockNP1));
        final NodeProperty<?> actualNP2 = actualNPs.get(1);
        assertThat(actualNP2, sameInstance(mockNP2));
        final NodeProperty<?> actualNP3 = actualNPs.get(2);
        assertIsEnvVarNPContaining(actualNP3, expectedEnvVars);
    }

    @Test
    public void constructorGivenSomeNodePropertiesIncludingEnvVarsThenIncludesIPAddressInEnvVars() throws Exception {
        // Given
        final String expectedIpAddress = "3.4.5.6";
        final ProvisioningActivity.Id stubId = new ProvisioningActivity.Id("id3");
        final String nodeName = "name3";
        final Server mockMetadata = mockServer(nodeName, expectedIpAddress);
        final String labelString = "thing";
        final SlaveOptions mockSlaveOptions = mock(SlaveOptions.class);
        when(mockSlaveOptions.getNumExecutors()).thenReturn(1);
        final NodeProperty<Node> mockNP1 = mockNodeProperty();
        final String envVar1Name = "VarName1";
        final String envVar1Value = "Value1";
        final String envVar2Name = "VarName2";
        final String envVar2Value = "Value2";
        final EnvironmentVariablesNodeProperty envVarNP = new EnvironmentVariablesNodeProperty(
                new EnvironmentVariablesNodeProperty.Entry(envVar1Name, envVar1Value),
                new EnvironmentVariablesNodeProperty.Entry(envVar2Name, envVar2Value));
        final NodeProperty<Node> mockNP3 = mockNodeProperty();
        final List<NodeProperty<?>> listOfMockNodeProperties = ImmutableList.of(mockNP1, envVarNP, mockNP3);
        when(mockSlaveOptions.getNodeProperties()).thenReturn(listOfMockNodeProperties);
        final Map<String, String> expectedEnvVars = ImmutableMap.of(envVar1Name, envVar1Value, envVar2Name,
                envVar2Value, EXPECTED_IP_ADDRESS_ENV_VAR_NAME, expectedIpAddress);

        // When
        JCloudsSlave instance = new JCloudsSlave(stubId, mockMetadata, labelString, mockSlaveOptions);

        // Then
        final List<NodeProperty<?>> actualNPs = instance.getNodeProperties().toList();
        assertThat(actualNPs.size(), equalTo(3));
        final NodeProperty<?> actualNP1 = actualNPs.get(0);
        assertThat(actualNP1, sameInstance(mockNP1));
        final NodeProperty<?> actualNP2 = actualNPs.get(1);
        assertIsEnvVarNPContaining(actualNP2, expectedEnvVars);
        final NodeProperty<?> actualNP3 = actualNPs.get(2);
        assertThat(actualNP3, sameInstance(mockNP3));
    }

    private static void assertIsEnvVarNPContaining(final NodeProperty<?> actual,
            final Map<String, String> expectedEnvVars) {
        assertThat(actual, instanceOf(EnvironmentVariablesNodeProperty.class));
        final EnvironmentVariablesNodeProperty actualEnvVarNP = (EnvironmentVariablesNodeProperty) actual;
        final Map<String, String> actualEnvVars = ImmutableMap.copyOf(actualEnvVarNP.getEnvVars());
        assertThat(actualEnvVars, equalTo(expectedEnvVars));
    }

    private static Server mockServer(final String nameToReturn, final String ipAddressToReturn) {
        final Address mockAddress = mock(Address.class);
        when(mockAddress.getAddr()).thenReturn(ipAddressToReturn);
        when(mockAddress.getVersion()).thenReturn(4);
        when(mockAddress.getType()).thenReturn("floating");
        final Addresses mockAddresses = mock(Addresses.class);
        when(mockAddresses.getAddresses()).thenReturn(ImmutableMap.of("key", ImmutableList.of(mockAddress)));
        final Server mockMetadata = mock(Server.class);
        when(mockMetadata.getName()).thenReturn(nameToReturn);
        when(mockMetadata.getAddresses()).thenReturn(mockAddresses);
        return mockMetadata;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Node> NodeProperty<T> mockNodeProperty() {
        return mock(NodeProperty.class);
    }
}
