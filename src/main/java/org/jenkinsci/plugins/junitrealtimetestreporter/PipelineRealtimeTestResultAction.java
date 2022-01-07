/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.junitrealtimetestreporter;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.AbortException;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.tasks.junit.JUnitParser;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.pipeline.JUnitResultsStepExecution;
import hudson.tasks.test.PipelineTestDetails;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import static java.util.Objects.requireNonNull;

class PipelineRealtimeTestResultAction extends AbstractRealtimeTestResultAction {

    private static final Logger LOGGER = Logger.getLogger(PipelineRealtimeTestResultAction.class.getName());

    final String id;
    private final String node;
    private final String workspace;
    private final boolean keepLongStdio;
    private final String glob;
    @CheckForNull
    private transient final StepContext context;

    PipelineRealtimeTestResultAction(
            String id,
            FilePath ws,
            boolean keepLongStdio,
            String glob,
            StepContext context
    ) {
        this.id = id;
        node = FilePathUtils.getNodeName(ws);
        workspace = ws.getRemote();
        this.keepLongStdio = keepLongStdio;
        this.glob = glob;
        this.context = context;
    }

    @Override
    public String getDisplayName() {
        if (node.isEmpty()) {
            return Messages.PipelineRealtimeTestResultAction_realtime_test_result_on_master();
        } else {
            return Messages.PipelineRealtimeTestResultAction_realtime_test_result_on_(node);
            // TODO include the branch or stage name (nearest enclosing LabelAction), as in jenkinsci/workflow-durable-task-step-plugin#2
        }
    }

    @Override
    public String getUrlName() {
        return "realtimeTestReport-" + id;
    }

    @Override
    protected TestResult parse() throws IOException, InterruptedException {
        FilePath ws = FilePathUtils.find(node, workspace);
        if (ws != null && ws.isDirectory()) {
            LOGGER.log(Level.FINE, "parsing {0} in {1} on node {2} for {3}", new Object[] {glob, workspace, node, run});

            FlowNode node = null;
            TaskListener listener = TaskListener.NULL;
            // lots of boilerplate code to get access to the flow node and listener but without saving
            // the StepContext via xstream in case of restarts, we default to using step context if it's available though
            if (context != null) {
                node = context.get(FlowNode.class);
                listener = context.get(TaskListener.class);
            } else {
                if (this.run instanceof FlowExecutionOwner.Executable) {
                    FlowExecutionOwner.Executable executable = (FlowExecutionOwner.Executable) this.run;
                    FlowExecutionOwner flowOwner = executable.asFlowExecutionOwner();
                    if (flowOwner != null) {
                        FlowExecution flowExecution = flowOwner.getOrNull();
                        if (flowExecution != null) {
                            node = flowExecution.getNode(id);
                        }
                        listener = flowOwner.getListener();
                    }
                }
            }

            PipelineTestDetails pipelineTestDetails = null;
            if (node != null) {
                List<FlowNode> enclosingBlocks = JUnitResultsStepExecution
                        .getEnclosingStagesAndParallels(requireNonNull(node));

                pipelineTestDetails = new PipelineTestDetails();
                pipelineTestDetails.setNodeId(id);
                pipelineTestDetails.setEnclosingBlocks(JUnitResultsStepExecution.getEnclosingBlockIds(enclosingBlocks));
                pipelineTestDetails.setEnclosingBlockNames(JUnitResultsStepExecution.getEnclosingBlockNames(enclosingBlocks));
            }

            return new JUnitParser(keepLongStdio, true)
                    .parseResult(glob, this.run, pipelineTestDetails, ws, null, listener);
        } else {
            throw new AbortException("skipping parse in nonexistent workspace for " +  run);
        }
    }

}
