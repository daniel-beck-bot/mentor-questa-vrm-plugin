/*
 * The MIT License
 *
 * Copyright 2016 Mentor Graphics.
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
package com.mentor.questa.vrm.jenkins;

import hudson.FilePath;
import hudson.model.Descriptor;
import com.mentor.questa.ucdb.jenkins.QuestaCoverageTestDataPublisher;
import hudson.AbortException;
import hudson.DescriptorExtensionList;
import hudson.Extension;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Proc;
import hudson.Util;
import hudson.model.Run;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResultAction;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import net.sf.json.JSONObject;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Publish VRM Results
 *
 *
 */
public class QuestaVrmPublisher extends Recorder implements SimpleBuildStep {

    private final String vrmdata;

    private boolean htmlReport = true;
    private boolean collectCoverage = true;

    private String vrunExec;
    private String vcoverExec;
    

    private String vrmhtmldir;

    private String extraArgs = "";

    private DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>> testDataPublishers;

    private Double healthScaleFactor = 1.0;
    
    private static final Logger logger = Logger.getLogger(QuestaVrmPublisher.class.getName());
    
    @DataBoundConstructor
    public QuestaVrmPublisher(String vrmdata) {
        this.vrmdata = vrmdata;
    }

    public static class OptionalVcoverExecBlock {
        private final String vcoverExec;

        @org.kohsuke.stapler.DataBoundConstructor
        public OptionalVcoverExecBlock(String vcoverExec) {
            this.vcoverExec = vcoverExec;
        }
    }
    
    public Double getHealthScaleFactor() {
        return healthScaleFactor;
    }

    @DataBoundSetter
    public final void setHealthScaleFactor(Double healthScaleFactor) {
        this.healthScaleFactor = healthScaleFactor;
    }

    public String getVrmdata() {
        return vrmdata;
    }

    @DataBoundSetter
    public final void setTestDataPublishers(@Nonnull List<? extends TestDataPublisher> testDataPublishers) {
        this.testDataPublishers = new DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>>(Saveable.NOOP);
        this.testDataPublishers.addAll(testDataPublishers);
    }

    public @Nonnull
    List<? extends TestDataPublisher> getTestDataPublishers() {
        return testDataPublishers == null ? Collections.<TestDataPublisher>emptyList() : testDataPublishers;
    }



    private void processTestDataPublishers(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener, QuestaVrmRegressionResult regressionResult) throws IOException, InterruptedException {
        TestResultAction action = build.getAction(TestResultAction.class);
        if (action == null) {
            return;
        }
        List<TestResultAction.Data> testData = new ArrayList<TestResultAction.Data>();

        if (isCollectCoverage()) {
            QuestaCoverageTestDataPublisher.Data d = (QuestaCoverageTestDataPublisher.Data) QuestaCoverageTestDataPublisher.getRegressionTestData(regressionResult.getMergeFiles(), resolveParametersInString(build, listener, getVrunExec()), resolveParametersInString(build, listener, getVcoverExec()), regressionResult.getRegressionBegin(), build, workspace, launcher, listener, action.getResult(), regressionResult.getVrmdata());
            if (d != null) {
                testData.add(d);
            }

        }

        for (TestDataPublisher testPub : getTestDataPublishers()) {
            TestResultAction.Data d = testPub.contributeTestData(build, workspace, launcher, listener, action.getResult());

            if (d != null) {
                testData.add(d);
            }

        }
        action.setData(testData);

    }

    private String constructCmdString(Run build, TaskListener listener) {
        String expandedVrmData = resolveParametersInString(build, listener, vrmdata);

        // Workaround for vrm windows path bug
        if (File.separator.equals("\\")) {
            expandedVrmData = expandedVrmData.replace('\\', '/');
        }
       
        String cmd = resolveParametersInString(build, listener, getVrunExec()) + " -vrmdata " + expandedVrmData + " -status  -json -notestname ";

        if (isHtmlReport()) {
            cmd += " -html -htmldir " + resolveParametersInString(build, listener, getVrmhtmldir());
            if (isCollectCoverage()) {
                cmd += " -covreport";
            }
        }
        //append extra args
        cmd+=" "+ resolveParametersInString(build, listener, getExtraArgs()); 
        return cmd;
    }

    private void addVrmBuildActions(Run  build, TaskListener listener, QuestaVrmRegressionResult regressionResult) {
        QuestaVrmRegressionBuildAction regressionAction = new QuestaVrmRegressionBuildAction(build, isHtmlReport());
        regressionAction.setResult(regressionResult, listener);
        build.addAction(regressionAction);
        build.addAction(new QuestaVrmHostAction(build));
    }

    @Override
    public void perform(Run<?,?> build, FilePath workspace ,Launcher launcher, TaskListener listener) throws IOException, InterruptedException {


        listener.getLogger().println("Recording VRM results...");

        synchronized (build) {

            String cmd = constructCmdString(build, listener);
            ByteArrayOutputStream vrunOutput = new ByteArrayOutputStream();
            try {
                ProcStarter ps = launcher.launch();
                ps.cmds(Util.tokenize(cmd)).envs(build.getEnvironment(listener)).stdin(null).stdout(vrunOutput).pwd(workspace);
                ps.quiet(true);
                Proc proc = launcher.launch(ps);
                proc.join();
                logger.log(Level.WARNING, "Output of vrun command '"+cmd+"':"+vrunOutput.toString());

            } catch (IOException e) {
                listener.getLogger().println("[ERROR]: Vrun executable \'" + getVrunExec() + "\' not found. Aborting storing VRM results. ");
                logger.log(Level.WARNING, "Output of vrun command '"+cmd+"':"+vrunOutput.toString());
                logger.log(Level.SEVERE, "Exception when running vrun command:"+e.toString());
		throw new AbortException("[ERROR]: Vrun executable \'" + getVrunExec() + "\' not found. Aborting storing VRM results. ");
            }
            
            QuestaVrmRegressionResult regressionResult;
            
            try {
                String vrmData = getVrmdata();
                vrmData = build.getEnvironment(listener).expand(vrmData);
                regressionResult = (QuestaVrmRegressionResult) new QuestaVrmResultsParser().parseResult(vrmData, build, workspace,  launcher, listener);
            } catch (AbortException a) {
                listener.getLogger().println("[ERROR]:"+a.getMessage() +" Please recheck your configuration. Aborting storing VRM results.");
                listener.getLogger().println("Output of vrun command \'" + cmd + "\':\n"+vrunOutput.toString());
                logger.log(Level.SEVERE, "Exception when parsing JSONfile:"+a.toString());
                throw new AbortException("[ERROR]:"+a.getMessage() +" please recheck your configuration. Aborting storing VRM results.");
            }

            QuestaVrmJunitProcessor vrmProcessor = new QuestaVrmJunitProcessor();

            vrmProcessor.perform(build, workspace, listener, listener.getLogger());

            // Add VRM build action(s)
            addVrmBuildActions(build, listener, regressionResult);
            
            // process data publishers
            processTestDataPublishers(build, workspace, launcher, listener, regressionResult);
            
            if (isHtmlReport()) {
                new QuestaVrmHTMLArchiver(getVrmhtmldir(), regressionResult.getCovHTMLReports()).perform(build, workspace, listener, listener.getLogger());
            }

            // Adjust build result if any of the regression actions failed
            if (regressionResult.getFailedActionCount() > 0) {
                build.setResult(Result.UNSTABLE);
            }

            listener.getLogger().println("Recording finished.");
            processDeletion(workspace);

        }

        
    }

    private void processDeletion(FilePath workspace) {
        try {
            workspace.child("vm-jUnit.xml").delete();
            workspace.child("json.js").delete();
        } catch (IOException e) {

        } catch (InterruptedException e) {
        }

    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * @return the vrmhtmldir
     */
    public String getVrmhtmldir() {
        if (vrmhtmldir == null) {
            vrmhtmldir = "vrmhtmldir";
        }
        return vrmhtmldir;
    }

    /**
     * @param vrmhtmldir the vrmhtmldir to set
     */
    @DataBoundSetter
    public final void setVrmhtmldir(String vrmhtmldir) {
        this.vrmhtmldir = vrmhtmldir;
    }

    @DataBoundSetter
    public final void setVrunExec(String vrunExec) {
        this.vrunExec = vrunExec;
    }

    /**
     * @return the htmlReport
     */
    public boolean isHtmlReport() {
        return htmlReport;
    }

    /**
     * @param htmlReport the htmlReport to set
     */
    @DataBoundSetter
    public final void setHtmlReport(boolean htmlReport) {
        this.htmlReport = htmlReport;
    }

    /**
     * @return the collectCoverage
     */
    public boolean isCollectCoverage() {
        return collectCoverage;
    }

    /**
     * @param collectCoverage the collectCoverage to set
     */
    @DataBoundSetter
    public final void setCollectCoverage(boolean collectCoverage) {
        this.collectCoverage = collectCoverage;
    }
    
    @DataBoundSetter
    public final void setEnableVcoverExec(OptionalVcoverExecBlock enableVcoverExec) {
        this.vcoverExec = (enableVcoverExec != null)? enableVcoverExec.vcoverExec : null;
    }
    


    /**
     * @return the vrunExec
     */
    public String getVrunExec() {
        if (vrunExec == null) {
            vrunExec = getDescriptor().getVrunExec();
        }
        return vrunExec;
    }

    /**
     * @return the vcoverExec
     */
    public String getVcoverExec() {
        return vcoverExec;
    }
    
    /**
     * @return the extraArgs
     */
    public String getExtraArgs() {
        if (extraArgs == null) {
            extraArgs = "";
        }
        return extraArgs;
    }

    /**
     * @param extraArgs the extraArgs to set
     */
    @DataBoundSetter
    public final void setExtraArgs(String extraArgs) {
        this.extraArgs = extraArgs;
    }

    protected static String resolveParametersInString(Run<?, ?> build, TaskListener listener, String input) {
        try {
            return build.getEnvironment(listener).expand(input);
        } catch (Exception e) {
            listener.getLogger().println("Failed to resolve parameters in string \""
                    + input + "\" due to following error:\n" + e.getMessage());
        }
        return input;
    }

    /**
     * {@inheritDoc}
     */
    @Extension @Symbol("questavrm")
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String vrunExec;

        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckVrmdata(
                @AncestorInPath AbstractProject project,
                @QueryParameter String value) throws IOException {

            if (project == null) {
                return FormValidation.ok();
            }
            if (value.length() == 0) {
                return FormValidation.error("Required!");
            }
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Publish Questa VRM Regression Results";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            vrunExec = json.get("vrunExec").toString();
            save();
            super.configure(req, json);
            return true;
        }

        public String getVrunExec() {
            if (vrunExec == null) {
                return "vrun";
            }
            return vrunExec;
        }
        
        public DescriptorExtensionList<TestDataPublisher, Descriptor<TestDataPublisher>> getTestDataPublishers() {
            DescriptorExtensionList<TestDataPublisher, Descriptor<TestDataPublisher>> originalList = TestDataPublisher.all();
            DescriptorExtensionList<TestDataPublisher, Descriptor<TestDataPublisher>> newList = DescriptorExtensionList.createDescriptorList(Jenkins.getInstance(), TestDataPublisher.class);

            for (Descriptor d : originalList) {

                if ((d instanceof QuestaCoverageTestDataPublisher.DescriptorImpl)) {
                    newList.remove(d);
                }
            }
            return newList;
        }

    }

}
