/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.test.integration.domain.suites;

import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT;
import static org.jboss.as.controller.client.helpers.ClientConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ARCHIVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.BYTES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT_OVERLAY;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ENABLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INCLUDE_DEFAULTS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INCLUDE_RUNTIME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INPUT_STREAM_INDEX;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RUNTIME_NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.UNDEPLOY;
import static org.jboss.as.test.deployment.trivial.ServiceActivatorDeployment.PROPERTIES_RESOURCE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.as.controller.PathAddress;

import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.test.deployment.trivial.ServiceActivatorDeploymentUtil;
import org.jboss.as.test.integration.domain.management.util.DomainTestSupport;
import org.jboss.as.test.integration.domain.management.util.DomainTestUtils;
import org.jboss.as.test.integration.management.util.MgmtOperationException;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.threads.AsyncFuture;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 * @author <a href="mailto:ehugonne@redhat.com">Emmanuel Hugonnet</a>  (c) 2015 Red Hat, inc.
 */
public class DeploymentOverlayTestCase {

    private static final int TIMEOUT = TimeoutUtil.adjust(20000);
    private static final String DEPLOYMENT_NAME = "deployment.jar";
    private static final String MAIN_RUNTIME_NAME = "main-deployment.jar";
    private static final String OTHER_RUNTIME_NAME = "other-deployment.jar";
    private static final PathElement DEPLOYMENT_PATH = PathElement.pathElement(DEPLOYMENT, DEPLOYMENT_NAME);
    private static final PathElement DEPLOYMENT_OVERLAY_PATH = PathElement.pathElement(DEPLOYMENT_OVERLAY, "test-overlay");
    private static final PathElement MAIN_SERVER_GROUP = PathElement.pathElement(SERVER_GROUP, "main-server-group");
    private static final PathElement OTHER_SERVER_GROUP = PathElement.pathElement(SERVER_GROUP, "other-server-group");
    private static DomainTestSupport testSupport;
    private static DomainClient masterClient;
    private static DomainClient slaveClient;

    private static final Properties properties = new Properties();
    private static final Properties properties2 = new Properties();

    @BeforeClass
    public static void setupDomain() throws Exception {
        testSupport = CLITestSuite.createSupport(DeploymentOverlayTestCase.class.getSimpleName());
        masterClient = testSupport.getDomainMasterLifecycleUtil().getDomainClient();
        slaveClient = testSupport.getDomainSlaveLifecycleUtil().getDomainClient();
        properties.clear();
        properties.put("service", "is new");

        properties2.clear();
        properties2.put("service", "is added");
    }

    @AfterClass
    public static void tearDownDomain() throws Exception {
        testSupport = null;
        masterClient.close();
        masterClient = null;
        slaveClient.close();
        slaveClient = null;
        CLITestSuite.stopSupport();
    }

    @After
    public void cleanup() throws IOException {
        try {
            cleanDeployment();
        } catch (MgmtOperationException e) {
            // ignored
        }
    }

    @Test
    public void testInstallAndOverlayDeploymentOnDC() throws IOException, MgmtOperationException {
        final JavaArchive archive = ServiceActivatorDeploymentUtil.createServiceActivatorDeploymentArchive("test-deployment.jar", properties);
        ModelNode result;
        try (InputStream is = archive.as(ZipExporter.class).exportAsInputStream()){
            AsyncFuture<ModelNode> future = masterClient.executeAsync(addDeployment(is), null);
            result = awaitSimpleOperationExecution(future);
        }
        assertTrue(Operations.isSuccessfulOutcome(result));
        ModelNode contentNode = readDeploymentResource(PathAddress.pathAddress(DEPLOYMENT_PATH)).require(CONTENT).require(0);
        assertTrue(contentNode.get(ARCHIVE).asBoolean(true));
        //Let's deploy it on main-server-group
        executeAsyncForResult(masterClient, deployOnServerGroup(MAIN_SERVER_GROUP, MAIN_RUNTIME_NAME));
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "main-three")), properties);
        executeAsyncForResult(masterClient, deployOnServerGroup(OTHER_SERVER_GROUP, OTHER_RUNTIME_NAME));
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties);
        executeAsyncForResult(masterClient, Operations.createOperation(ADD, PathAddress.pathAddress(DEPLOYMENT_OVERLAY_PATH).toModelNode()));
        //Add some content
        executeAsyncForResult(masterClient, addOverlayContent(properties2, "Overlay content"));
        //Add overlay on server-groups
        executeAsyncForResult(masterClient, Operations.createOperation(ADD, PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode()));
        executeAsyncForResult(masterClient, Operations.createOperation(ADD, PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, MAIN_RUNTIME_NAME)).toModelNode()));
        executeAsyncForResult(masterClient, Operations.createOperation(ADD, PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode()));
        executeAsyncForResult(masterClient, Operations.createOperation(ADD, PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, OTHER_RUNTIME_NAME)).toModelNode()));
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "master"),
                PathElement.pathElement(SERVER, "main-one")), properties);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "main-three")), properties);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties);
        ModelNode redeployNothingOperation = Operations.createOperation("redeploy-links", PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode());
        redeployNothingOperation.get("deployments").setEmptyList();
        redeployNothingOperation.get("deployments").add(OTHER_RUNTIME_NAME);//Doesn't exist
        redeployNothingOperation.get("deployments").add("inexisting.jar");
        executeAsyncForResult(masterClient, redeployNothingOperation);
        //Check that nothing happened
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "master"),
                PathElement.pathElement(SERVER, "main-one")), properties);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "main-three")), properties);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties);
        executeAsyncForResult(masterClient, Operations.createOperation("redeploy-links", PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode()));
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "master"),
                PathElement.pathElement(SERVER, "main-one")), properties2);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "main-three")), properties2);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties);
        ServiceActivatorDeploymentUtil.validateProperties(slaveClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties);
        executeAsyncForResult(masterClient, Operations.createOperation(REMOVE, PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, MAIN_RUNTIME_NAME)).toModelNode()));
        executeAsyncForResult(masterClient, Operations.createOperation(REMOVE, PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, OTHER_RUNTIME_NAME)).toModelNode()));
        executeAsyncForResult(masterClient, Operations.createOperation(ADD, PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, OTHER_RUNTIME_NAME)).toModelNode()));
        executeAsyncForResult(masterClient, Operations.createOperation("redeploy-links", PathAddress.pathAddress(DEPLOYMENT_OVERLAY_PATH).toModelNode()));
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "main-three")), properties2);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties2);
        redeployNothingOperation = Operations.createOperation("redeploy-links", PathAddress.pathAddress(DEPLOYMENT_OVERLAY_PATH).toModelNode());
        redeployNothingOperation.get("deployments").setEmptyList();
        redeployNothingOperation.get("deployments").add(OTHER_RUNTIME_NAME);
        redeployNothingOperation.get("deployments").add("inexisting.jar");
        executeAsyncForResult(masterClient, redeployNothingOperation);
        //Check that nothing happened
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "main-three")), properties2);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties2);
        ModelNode failingOperation = Operations.createOperation("redeploy-links", PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode());
        failingOperation.get("deployments").setEmptyList();
        failingOperation.get("deployments").add(OTHER_RUNTIME_NAME);
        executeAsyncForFailure(slaveClient, failingOperation, "WFLYDC0032: Operation redeploy-links for address " + PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode().toString() + " can only be handled by the master Domain Controller; this host is not the master Domain Controller");
        ModelNode removeLinkOp = Operations.createOperation(REMOVE, PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, OTHER_RUNTIME_NAME)).toModelNode());
        removeLinkOp.get("redeploy-affected").set(true);
        executeAsyncForResult(masterClient, removeLinkOp);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "main-three")), properties2);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties);
        ServiceActivatorDeploymentUtil.validateProperties(slaveClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties);
        executeAsyncForResult(masterClient, Operations.createOperation(ADD, PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, OTHER_RUNTIME_NAME)).toModelNode()));
        executeAsyncForResult(masterClient, Operations.createOperation(ADD, PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, MAIN_RUNTIME_NAME)).toModelNode()));
        ModelNode redeployOp = Operations.createOperation("redeploy-links", PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode());
        redeployOp.get("deployments").setEmptyList();
        redeployOp.get("deployments").add(MAIN_RUNTIME_NAME);
        redeployOp.get("deployments").add("inexisting.jar");
        executeAsyncForResult(masterClient, redeployOp);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "main-three")), properties2);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties);
        ServiceActivatorDeploymentUtil.validateProperties(slaveClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties);
        redeployOp = Operations.createOperation("redeploy-links", PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode());
        redeployOp.get("deployments").setEmptyList();
        redeployOp.get("deployments").add(OTHER_RUNTIME_NAME);
        executeAsyncForResult(masterClient, redeployOp);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "main-three")), properties2);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties2);
        ServiceActivatorDeploymentUtil.validateProperties(slaveClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties2);
        executeAsyncForResult(masterClient,
                Operations.createReadResourceOperation(PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, MAIN_RUNTIME_NAME)).toModelNode()));
        executeAsyncForResult(masterClient,
                Operations.createReadResourceOperation(PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, OTHER_RUNTIME_NAME)).toModelNode()));
        Operations.CompositeOperationBuilder builder = Operations.CompositeOperationBuilder.create();
        removeLinkOp = Operations.createOperation(REMOVE, PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, MAIN_RUNTIME_NAME)).toModelNode());
        removeLinkOp.get("redeploy-affected").set(true);
        builder.addStep(removeLinkOp);
        ModelNode removeOverlayOp = Operations.createRemoveOperation(PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode());
        builder.addStep(removeOverlayOp);
        removeLinkOp = Operations.createOperation(REMOVE, PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, OTHER_RUNTIME_NAME)).toModelNode());
        removeLinkOp.get("redeploy-affected").set(true);
        builder.addStep(removeLinkOp);
        removeOverlayOp = Operations.createRemoveOperation(PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode());
        builder.addStep(removeOverlayOp);
        executeAsyncForResult(masterClient, builder.build().getOperation());
        executeAsyncForFailure(masterClient,
                Operations.createReadResourceOperation(PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, MAIN_RUNTIME_NAME)).toModelNode()),
                ControllerLogger.ROOT_LOGGER.managementResourceNotFound(PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH)).getMessage());
        executeAsyncForFailure(masterClient,
                Operations.createReadResourceOperation(PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH, PathElement.pathElement(DEPLOYMENT, OTHER_RUNTIME_NAME)).toModelNode()),
                ControllerLogger.ROOT_LOGGER.managementResourceNotFound(PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH)).getMessage());
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "master"),
                PathElement.pathElement(SERVER, "main-one")), properties);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "main-three")), properties);
        ServiceActivatorDeploymentUtil.validateProperties(masterClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties);
        ServiceActivatorDeploymentUtil.validateProperties(slaveClient, PathAddress.pathAddress(
                PathElement.pathElement(HOST, "slave"),
                PathElement.pathElement(SERVER, "other-two")), properties);
    }

    private void executeAsyncForResult(DomainClient client, ModelNode op) {
        AsyncFuture<ModelNode> future = client.executeAsync(op, null);
        ModelNode response = awaitSimpleOperationExecution(future);
        assertTrue(response.toJSONString(true), Operations.isSuccessfulOutcome(response));
    }

    private void executeAsyncForDomainFailure(DomainClient client, ModelNode op, String failureDescription) {
        AsyncFuture<ModelNode> future = client.executeAsync(op, null);
        ModelNode response = awaitSimpleOperationExecution(future);
        assertFalse(response.toJSONString(true), Operations.isSuccessfulOutcome(response));
        assertTrue(response.toJSONString(true), Operations.getFailureDescription(response).hasDefined("domain-failure-description"));
        assertTrue(Operations.getFailureDescription(response).get("domain-failure-description").asString() + " doesn't contain " + failureDescription,
                Operations.getFailureDescription(response).get("domain-failure-description").asString().contains(failureDescription));
    }

    private void executeAsyncForFailure(DomainClient client, ModelNode op, String failureDescription) {
        AsyncFuture<ModelNode> future = client.executeAsync(op, null);
        ModelNode response = awaitSimpleOperationExecution(future);
        assertFalse(response.toJSONString(true), Operations.isSuccessfulOutcome(response));
        assertEquals(failureDescription, Operations.getFailureDescription(response).asString());
    }

    private ModelNode addOverlayContent(Properties props, String comment) throws IOException {
        ModelNode op = Operations.createOperation(ADD, PathAddress.pathAddress(DEPLOYMENT_OVERLAY_PATH).append(CONTENT, PROPERTIES_RESOURCE).toModelNode());
        try (StringWriter writer = new StringWriter()) {
            props.store(writer, comment);
            op.get(CONTENT).get(BYTES).set(writer.toString().getBytes(StandardCharsets.UTF_8));
        }
        return op;
    }

    private ModelNode readDeploymentResource(PathAddress address) {
        ModelNode operation = Operations.createReadResourceOperation(address.toModelNode());
        operation.get(INCLUDE_RUNTIME).set(true);
        operation.get(INCLUDE_DEFAULTS).set(true);
        AsyncFuture<ModelNode> future = masterClient.executeAsync(operation, null);
        ModelNode result = awaitSimpleOperationExecution(future);
        assertTrue(Operations.isSuccessfulOutcome(result));
        return Operations.readResult(result);
    }

    private ModelNode awaitSimpleOperationExecution(Future<ModelNode> future) {
        try {
            return future.get(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException(e);
        }
    }

    private Operation addDeployment(InputStream attachment) throws MalformedURLException {
        ModelNode operation = Operations.createAddOperation(PathAddress.pathAddress(DEPLOYMENT_PATH).toModelNode());
        ModelNode content = new ModelNode();
        content.get(INPUT_STREAM_INDEX).set(0);
        operation.get(CONTENT).add(content);
        return Operation.Factory.create(operation, Collections.singletonList(attachment));
    }

    private ModelNode deployOnServerGroup(PathElement group, String runtimeName) throws MalformedURLException {
        ModelNode operation = Operations.createOperation(ADD, PathAddress.pathAddress(group, DEPLOYMENT_PATH).toModelNode());
        operation.get(RUNTIME_NAME).set(runtimeName);
        operation.get(ENABLED).set(true);
        return operation;
    }

    private ModelNode undeployAndRemoveOp() throws MalformedURLException {
        ModelNode op = new ModelNode();
        op.get(OP).set(COMPOSITE);
        ModelNode steps = op.get(STEPS);
        ModelNode sgDep = PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_PATH).toModelNode();
        steps.add(Operations.createOperation(UNDEPLOY, sgDep));
        steps.add(Operations.createRemoveOperation(sgDep));
        sgDep = PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_PATH).toModelNode();
        steps.add(Operations.createOperation(UNDEPLOY, sgDep));
        steps.add(Operations.createRemoveOperation(sgDep));
        steps.add(Operations.createRemoveOperation(PathAddress.pathAddress(DEPLOYMENT_PATH).toModelNode()));
        steps.add(Operations.createRemoveOperation(PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode()));
        steps.add(Operations.createRemoveOperation(PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode()));
        steps.add(Operations.createRemoveOperation(PathAddress.pathAddress(DEPLOYMENT_OVERLAY_PATH).toModelNode()));
        steps.add(Operations.createRemoveOperation(PathAddress.pathAddress(MAIN_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode()));
        steps.add(Operations.createRemoveOperation(PathAddress.pathAddress(OTHER_SERVER_GROUP, DEPLOYMENT_OVERLAY_PATH).toModelNode()));
        steps.add(Operations.createRemoveOperation(PathAddress.pathAddress(DEPLOYMENT_OVERLAY_PATH).toModelNode()));
        return op;
    }

    private void cleanDeployment() throws IOException, MgmtOperationException {
        DomainTestUtils.executeForResult(undeployAndRemoveOp(), masterClient);
    }
}
