package org.lamport.tla.toolbox.tool.tlc.launch;

import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate;
import org.eclipse.jface.dialogs.MessageDialog;
import org.lamport.tla.toolbox.spec.parser.ParseResult;
import org.lamport.tla.toolbox.tool.IParseResult;
import org.lamport.tla.toolbox.tool.ToolboxHandle;
import org.lamport.tla.toolbox.tool.tlc.TLCActivator;
import org.lamport.tla.toolbox.tool.tlc.job.TLCJob;
import org.lamport.tla.toolbox.tool.tlc.job.TLCProcessJob;
import org.lamport.tla.toolbox.tool.tlc.model.TypedSet;
import org.lamport.tla.toolbox.tool.tlc.util.ModelHelper;
import org.lamport.tla.toolbox.tool.tlc.util.ModelWriter;
import org.lamport.tla.toolbox.util.ResourceHelper;
import org.lamport.tla.toolbox.util.TLAMarkerInformationHolder;
import org.lamport.tla.toolbox.util.UIHelper;

import tla2sany.semantic.OpDefNode;

/**
 * Methods in this class are executed when the user clicks the explore
 * button on the trace explorer. It extends {@link TLCModelLaunchDelegate} in order
 * to have the methods {@link TLCModelLaunchDelegate#getLaunch(ILaunchConfiguration, String)} and
 * {@link TLCModelLaunchDelegate#preLaunchCheck(ILaunchConfiguration, String, IProgressMonitor)}. This class
 * overrides the other methods in {@link TLCModelLaunchDelegate}.
 * 
 * In particular, it overrides buildForLaunch(), finalLaunchCheck(), and launch().
 * 
 * When a user clicks the explore button and there is an error trace, the method
 * doExplore() in TraceExplorerComposite is called. This method launches an ILaunchConfiguration
 * in the mode that corresponds to this delegate (as specified in the plugin.xml file for this plugin).
 * 
 * Eventually, the methods buildForLaunch(), finalLaunchCheck(), and launch() are called, in that order.
 * 
 * The first method, buildForLaunch() writes data to TE.tla so that SANY can be run on that module in the next
 * method, finalLaunchCheck(). buildForLaunch() also copies spec modules to the model directory if the model is not
 * locked.
 * 
 * The second method, finalLaunchCheck(), calls SANY on TE.tla. If there are parse errors, these errors are presented
 * to the user, and the launch terminates. If there are not parse errors, this method uses the {@link ParseResult} object
 * to determine the level of each trace explorer expression. It is necessary to determine if each expression contains
 * primed variables or not. This is explained later in these comments. Once the level of each expression is determined,
 * finalLaunchCheck() rewrites contents to TE.tla and writes contents to TE.cfg. Also, TE.out is redundantly cleared (it
 * is also cleared in buildForLaunch()).
 * 
 * The third method, launch(), is called if and only if finalLaunchCheck() returns true. It creates an instance of
 * {@link TLCProcessJob} which launches TLC.
 * 
 * 
 * 
 * @author Daniel Ricketts
 *
 */
public class TraceExplorerDelegate extends TLCModelLaunchDelegate implements ILaunchConfigurationDelegate,
        IModelConfigurationConstants, IConfigurationConstants
{
    public static final String MODE_TRACE_EXPLORE = "exploreTrace";

    private static final String EMPTY_STRING = "";

    private TraceExpressionInformationHolder[] traceExpressionData;
    private IFile tlaFile;
    private IFile cfgFile;
    private IFile outFile;

    /**
     * Writes data to TE.tla so that SANY can be run on that module in the next
     * method, finalLaunchCheck(). As a side effect of using the ModelWriter
     * class in the implementation of this method, some contents are written to TE.cfg.
     * This does not matter because TE.cfg is not used until after TLC is called in the
     * launch() method, which occurs after the contents of TE.cfg are re-written in the
     * method finalLaunchCheck(). This method also copies spec modules to the
     * model directory if the model is not locked.
     * 
     * The following items are written to TE.tla:                         
     *                                                                         
     * 1.) Name of module and EXTENDS specRootModule, TLC                      
     * 2.) Variable declaration for each trace exploration expression          
     * 3.) Definition of each trace exploration expression                     
     * 4.) values of constants                                                 
     * 5.) additional model values                                             
     * 6.) additional definitions                                              
     * 7.) definition overrides                                                
     * 8.) Initial state predicate without trace exploration expressions       
     * 9.) Next state action without trace exploration expressions             
     *                                                                         
     * The initial state predicate and next state action are written without   
     * the trace exploration expressions because we don't know at this point if
     * the expressions contain primed variables. We figure that out by parsing
     * in the method finalLaunchCheck(). We declare the variables here to make
     * sure that they have not been declared already in the spec. This is also
     * determined during parsing.
     * 
     * We write the initial state predicate and next state action with the     
     * variables from the original trace in order to detect any parse errors   
     * caused by changes to the spec or model if the model is unlocked. For    
     * example, the user could remove a variable from the spec. If the model is
     * unlocked, the trace explorer will be run against the new version of the 
     * spec. This will cause a parse error that will be detected because the   
     * initial state predicate will contain the variable that has been removed 
     * from the spec.
     * 
     * This is best illustrated with an example. The trace is the following:

    STATE 1: <Initial predicate>
    /\ x = 0
    /\ y = 0

    STATE 2: <Action line 8, col 3 to line 9, col 15 of module Test>
    /\ x = 1
    /\ y = 0

    STATE 3: <Action line 8, col 3 to line 9, col 15 of module Test>
    /\ x = 2
    /\ y = 1

    STATE 4: <Action line 8, col 3 to line 9, col 15 of module Test>
    /\ x = 3
    /\ y = 3

    The user wants to evaluate two expressions:

    x + y
    x' > y
    
    The following is the TE.tla file produced by this method:

    ---- MODULE TE ----
    EXTENDS TETest2, TLC

    \* TRACE EXPLORER variable declaration @traceExploreExpressions
    VARIABLES __trace_var_12627816375173000,__trace_var_12627816375435000
    ----

    \* TRACE EXPLORER identifier definition @traceExploreExpressions
    trace_def_12627816375072000 == x + y
    trace_def_12627816375334000 == x' > y
    ----

    \* TRACE INIT definitiontraceExploreInit
    init_12627816375536000 == /\  x=0/\  y=0
    ----

    \* TRACE NEXT definitiontraceExploreNext
    next_12627816375637000 == \/(/\  x=0/\  y=0/\  x'=1/\  y'=0)
    \/(/\  x=1/\  y=0/\  x'=2/\  y'=1)
    \/(/\  x=2/\  y=1/\  x'=3/\  y'=3)
    ----

    ====

    
     * As explained before, the init and next identifiers are defined only for parsing
     * purposes; they are not used to run TLC because they do not contain the trace
     * explorer expressions. They are simply defined in order to ensure that they will parse.
     * If the user has removed the variable x from the spec and the model for which trace
     * exploration is being run is unlocked, then the parsing of TE.tla will fail, the message
     * will be displayed to the user, and TLC will not be launched. This parsing occurs in
     * finalLaunchCheck().
     * 
     * If parsing succeeds in finalLaunchCheck(), then the contents used when running TLC are
     * written to TE.tla and TE.cfg. This is expained in the comments for finalLaunchCheck().
     */
    public boolean buildForLaunch(ILaunchConfiguration config, String mode, IProgressMonitor monitor)
            throws CoreException
    {

        int STEP = 100;

        // retrieve the project containing the specification
        IProject project = ResourceHelper.getProject(specName);
        IFolder modelFolder = project.getFolder(config.getAttribute(MODEL_NAME, EMPTY_STRING));
        if (!modelFolder.exists())
        {
            throw new CoreException(new Status(IStatus.ERROR, TLCActivator.PLUGIN_ID,
                    "Trace explorer was run and the model folder does not exist. This is a bug."));
        }
        IPath targetFolderPath = modelFolder.getProjectRelativePath().addTrailingSeparator();

        // create the handles: TE.tla, TE.cfg and TE.out
        tlaFile = project.getFile(targetFolderPath.append(ModelHelper.TE_FILE_TLA));
        cfgFile = project.getFile(targetFolderPath.append(ModelHelper.TE_FILE_CFG));
        outFile = project.getFile(targetFolderPath.append(ModelHelper.TE_FILE_OUT));

        TLCActivator.logDebug("Writing files to: " + targetFolderPath.toOSString());

        IFile[] files = new IFile[] { tlaFile, cfgFile, outFile };

        /*
         * We want to copy spec files to the model folder only if
         * the model is not locked. Before copying, the previous spec
         * files must be deleted.
         */
        if (!ModelHelper.isModelLocked(config))
        {

            /******************************************************************
             * This code deletes all existing files in the model folder except*
             * for the checkpoint folder, if it exists.                       *
             ******************************************************************/
            final IResource[] members = modelFolder.members();
            // erase everything inside
            if (members.length == 0)
            {
                monitor.worked(STEP);
            } else
            {
                // Get the checkpoint folder in order to avoid
                // deleting that folder.
                // This ModelHelper method should return an array of
                // size one because there should only be one checkpoint
                // folder.
                final IResource[] checkpoints = ModelHelper.getCheckpoints(config, false);

                ISchedulingRule deleteRule = ResourceHelper.getDeleteRule(members);

                // delete files
                ResourcesPlugin.getWorkspace().run(new IWorkspaceRunnable() {

                    public void run(IProgressMonitor monitor) throws CoreException
                    {

                        monitor.beginTask("Deleting files", members.length);
                        // delete the members of the target
                        // directory
                        for (int i = 0; i < members.length; i++)
                        {
                            try
                            {
                                if ((checkpoints.length > 0 && checkpoints[0].equals(members[i]))
                                        || members[i].getName().equals(ModelHelper.FILE_CFG)
                                        || members[i].getName().equals(ModelHelper.FILE_TLA)
                                        || members[i].getName().equals(ModelHelper.FILE_OUT))
                                {
                                    // We don't want to delete the checkpoints folder
                                    // or any of the MC files.
                                    continue;
                                }
                                members[i].delete(IResource.FORCE, new SubProgressMonitor(monitor, 1));
                            } catch (CoreException e)
                            {
                                // catch the exception if
                                // deletion failed, and just
                                // ignore this fact
                                // FIXME this should be fixed at
                                // some later point in time
                                TLCActivator.logError("Error deleting a file " + members[i].getLocation(), e);
                            }
                        }
                        monitor.done();
                    }
                }, deleteRule, IWorkspace.AVOID_UPDATE, new SubProgressMonitor(monitor, STEP));
            }
            /******************************************************************
             * Finished deleting files.                                       *
             ******************************************************************/

            /******************************************************************
             * This code copies all spec module files to the model folder.    *
             ******************************************************************/
            monitor.subTask("Copying files");

            // retrieve the root file
            IFile specRootFile = ResourceHelper.getLinkedFile(project, specRootFilename, false);
            if (specRootFile == null)
            {
                // root module file not found
                throw new CoreException(new Status(IStatus.ERROR, TLCActivator.PLUGIN_ID,
                        "Error accessing the root module " + specRootFilename));
            }

            // copy
            specRootFile.copy(targetFolderPath.append(specRootFile.getProjectRelativePath()), IResource.DERIVED
                    | IResource.FORCE, new SubProgressMonitor(monitor, 1));
            // find the result
            IResource specRootFileCopy = modelFolder.findMember(specRootFile.getProjectRelativePath());

            // react if no result
            if (specRootFileCopy == null)
            {
                throw new CoreException(new Status(IStatus.ERROR, TLCActivator.PLUGIN_ID, "Error copying "
                        + specRootFilename + " into " + targetFolderPath.toOSString()));
            }

            ModelHelper.copyExtendedModuleFiles(specRootFile, targetFolderPath, monitor, STEP, project);

            /******************************************************************
             * Finished copying files.                                        *
             ******************************************************************/

        }

        /***************************************************************************
         * Create the TE.tla, TE.cfg, and TE.out files if they don't exist and set *
         * the contents equal to "".                                               *
         ***************************************************************************/
        ModelHelper.createOrClearFiles(files, monitor);

        /***************************************************************************
         * Write the contents to TE.tla (and some irrelevant stuff to TE.cfg) as   *
         * explained in the main comments for this method.                         *
         ***************************************************************************/
        monitor.worked(STEP);
        monitor.subTask("Creating contents");

        ModelWriter writer = new ModelWriter();

        // add extend primer
        writer.addPrimer(ModelHelper.TE_MODEL_NAME, ResourceHelper.getModuleName(specRootFilename));

        /*
         * The following writes variable declarations and identifier definitions
         * for the trace explorer expressions. It also stores information about
         * each expression in traceExpressionData. In particular, it stores the variable
         * name, identifier, and expression string for each expression. This is used
         * in finalLaunchCheck() for re-writing contents to TE.tla and TE.cfg, if parsing
         * succeeds. This is necessary for two reasons:
         * 
         * 1.) We want to use the same variable name prior to running SANY as we do for
         * the TE.tla file that is to be run by TLC. The SANY run is used to determine if
         * those variable names are already declared in the spec, so if parsing succeeds,
         * we know that those variable names can be used again with TLC.
         * 
         * 2.) We use the identifier assigned to each expression to determine the level
         * of each expression. This is done in finalLaunchCheck() using the ParseResult
         * object returned by SANY.
         */
        traceExpressionData = writer.addTraceExploreVariablesPreParse(ModelHelper.deserializeFormulaList(config
                .getAttribute(IModelConfigurationConstants.TRACE_EXPLORE_EXPRESSIONS, new Vector())),
                TRACE_EXPLORE_EXPRESSIONS);

        writeModelInfo(config, writer);

        // add the initial state predicate and next state action without
        // the trace exploration expressions in order to determine if they parse
        writer.addTraceStateDefsPreParse(config.getAttribute(IModelConfigurationConstants.TRACE_INIT, EMPTY_STRING),
                config.getAttribute(IModelConfigurationConstants.TRACE_NEXT, new Vector()));

        monitor.worked(STEP);
        monitor.subTask("Writing contents");

        // Write the contents to the files
        writer.writeFiles(tlaFile, cfgFile, monitor);

        // do not want to rebuild the workspace
        return false;
    }

    /**
     * We use this method to check for parsing errors and to determine the level
     * of each trace explorer expression, i.e. whether there are primed variables or not.
     * If an expression is a temporal formula, this should show an error to the user.
     */
    public boolean finalLaunchCheck(ILaunchConfiguration configuration, String mode, IProgressMonitor monitor)
            throws CoreException
    {
        monitor.beginTask("Verifying model files", 4);

        IProject project = ResourceHelper.getProject(specName);
        IFolder launchDir = project.getFolder(modelName);
        IFile rootModule = launchDir.getFile(ModelHelper.TE_FILE_TLA);

        monitor.worked(1);
        // parse the TE.tla file
        IParseResult parseResult = ToolboxHandle.parseModule(rootModule, new SubProgressMonitor(monitor, 1), false,
                false);
        Assert
                .isTrue(parseResult instanceof ParseResult,
                        "Object returned by parsing the module is not an instance of ParseResult. This is not expected by the toolbox.");

        /***********************************************************************************
         * Check for parsing errors first.                                                 *
         ***********************************************************************************/
        if (parseResult.getDetectedErrors().size() > 0)
        {
            /*
             * This displays the parse errors to the user in an error
             * dialog. It attempts to replace messages containing references
             * to locations to module TE with the string from that location.
             */
            StringBuffer errorMessage = new StringBuffer();
            Iterator it = parseResult.getDetectedErrors().iterator();
            while (it.hasNext())
            {
                Object next = it.next();
                if (next instanceof TLAMarkerInformationHolder)
                {
                    errorMessage.append(((TLAMarkerInformationHolder) next).getMessage() + "\n");
                } else
                {
                    TLCActivator
                            .logDebug("Parse error while running trace explorer not represented by TLAMarkerInformationHolder."
                                    + "This is unexpected.");
                }
            }
            MessageDialog.openError(UIHelper.getShellProvider().getShell(),
                    "Parsing error when running trace explorer", errorMessage.toString());
            return false;
        }

        /***********************************************************************************
         * There are no parsing errors. Now use the parse result to determine the level of *
         * each trace explorer expression, where the level indicates the TLA level of the  *  
         * expression as follows.                                                          *
         *                                                                                 *
         *   0 : Constant Level                                                            *               
         *   1 : State Level                                                               *                                       
         *   2 : Action Level                                                              *                                            
         *   3 : Temporal Level                                                            *
         *                                                                                 *
         * If an expression is level 3, this should cause an error to be displayed to the  *
         * user.                                                                           *
         ***********************************************************************************/
        /*
         * First get the OpDefNodes for the root module TE.tla
         * Put them in a hashtable for efficiency in retrieving them.
         */
        OpDefNode[] opDefNodes = ((ParseResult) parseResult).getSpecObj().getExternalModuleTable().getRootModule()
                .getOpDefs();
        Hashtable nodeTable = new Hashtable(opDefNodes.length);

        Assert.isNotNull(opDefNodes, "OpDefNodes[] from parsing TE.tla is null. This is a bug.");
        for (int j = 0; j < opDefNodes.length; j++)
        {
            String key = opDefNodes[j].getName().toString();
            nodeTable.put(key, opDefNodes[j]);
        }

        /*
         * Set the level for each trace expression using the corresponding OpDefNode
         */
        for (int i = 0; i < traceExpressionData.length; i++)
        {
            OpDefNode opDefNode = (OpDefNode) nodeTable.get(traceExpressionData[i].getIdentifier());
            traceExpressionData[i].setLevel(opDefNode.getBody().getLevel());
            // TODO check for temporal formulas (level 3)
        }

        /*************************************************************************************
         * Now clear the TE.tla and TE.cfg and write the correct contents to those files.    *
         * The following items are written to those files:                                   *
         *                                                                                   *
         * 1.) Name of module and EXTENDS specRootModule, TLC                                *
         * 2.) Variable declaration for each trace exploration expression                    *
         * 3.) Definition of each trace exploration expression                               *
         * 4.) values of constants                                                           *
         * 5.) additional model values                                                       *
         * 6.) additional definitions                                                        *
         * 7.) definition overrides                                                          *
         * 8.) Init and Next including trace explorer variables                              *
         *************************************************************************************/
        ModelHelper.createOrClearFiles(new IFile[] { tlaFile, cfgFile, outFile }, monitor);

        monitor.subTask("Creating contents");

        ModelWriter writer = new ModelWriter();

        // add comments giving information about the level of each expression and
        // which variable corresponds to which expression
        writer.addTraceExplorerExpressionInfoComments(traceExpressionData);

        // add extend primer
        writer.addPrimer(ModelHelper.TE_MODEL_NAME, ResourceHelper.getModuleName(specRootFilename));

        // variables and definitions for trace explorer expressions
        writer.addTraceExprVarDecsAndDefsPostParse(traceExpressionData, TRACE_EXPLORE_EXPRESSIONS);

        writeModelInfo(configuration, writer);

        writer.addFormulaList(ModelWriter.createTraceInitContent(configuration.getAttribute(TRACE_INIT, EMPTY_STRING),
                traceExpressionData), "INIT", "traceExploreInit");
        writer.addFormulaList(ModelWriter.createTraceNextContent(configuration.getAttribute(TRACE_NEXT, new Vector()),
                traceExpressionData), "NEXT", "traceExploreNext");

        boolean isBackToState = configuration.getAttribute(IS_TRACE_BACK_TO_STATE, false);
        boolean isStuttering = configuration.getAttribute(IS_TRACE_STUTTERING, false);

        String finalStateConj = configuration.getAttribute(TRACE_FINAL_STATE, EMPTY_STRING);

        if (isStuttering)
        {
            writer.addStutteringPropertyForTraceExplorer(finalStateConj);
        } else if (isBackToState)
        {
            writer.addBackToStatePropertyForTraceExplorer(finalStateConj, configuration.getAttribute(
                    TRACE_BACK_TO_STATE, EMPTY_STRING));
        } else
        {
            writer.addInvariantForTraceExplorer(finalStateConj);
        }

        writer.writeFiles(tlaFile, cfgFile, monitor);

        // retrieve the model folder
        IFolder modelFolder = project.getFolder(modelName);
        // refresh the model folder
        modelFolder.refreshLocal(IResource.DEPTH_ONE, new SubProgressMonitor(monitor, 100));

        // launch should proceed
        return true;
    }

    public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor)
            throws CoreException
    {
        System.out.println("launch called");
        // check the modes
        if (!MODE_TRACE_EXPLORE.equals(mode))
        {
            throw new CoreException(
                    new Status(IStatus.ERROR, TLCActivator.PLUGIN_ID, "Unsupported launch mode " + mode));
        }

        // retrieve the project containing the specification
        IProject project = ResourceHelper.getProject(specName);
        if (project == null)
        {
            // project could not be found
            throw new CoreException(new Status(IStatus.ERROR, TLCActivator.PLUGIN_ID,
                    "Error accessing the spec project " + specName));
        }

        TLCJob tlcjob = new TLCProcessJob(specName, modelName, launch);
        tlcjob.setWorkers(1);
        tlcjob.setPriority(Job.LONG);
        tlcjob.setUser(true);
        // The TLC job itself does not do any file IO
        tlcjob.setRule(mutexRule);

        tlcjob.schedule();
    }

    /**
     * Writes constants, model values, new definitions, and overrides to the model writer.
     * 
     * @param config
     * @param writer
     * @throws CoreException
     */
    private void writeModelInfo(ILaunchConfiguration config, ModelWriter writer) throws CoreException
    {
        // constants list
        List constants = ModelHelper.deserializeAssignmentList(config.getAttribute(MODEL_PARAMETER_CONSTANTS,
                new Vector()));

        // the advanced model values
        TypedSet modelValues = TypedSet.parseSet(config.getAttribute(MODEL_PARAMETER_MODEL_VALUES, EMPTY_STRING));

        // add constants and model values
        writer.addConstants(constants, modelValues, MODEL_PARAMETER_CONSTANTS, MODEL_PARAMETER_MODEL_VALUES);

        // new definitions
        writer.addNewDefinitions(config.getAttribute(MODEL_PARAMETER_NEW_DEFINITIONS, EMPTY_STRING),
                MODEL_PARAMETER_NEW_DEFINITIONS);

        // definition overrides list
        List overrides = ModelHelper.deserializeAssignmentList(config.getAttribute(MODEL_PARAMETER_DEFINITIONS,
                new Vector()));
        writer.addFormulaList(ModelWriter.createOverridesContent(overrides, ModelWriter.DEFOV_SCHEME), "CONSTANT",
                MODEL_PARAMETER_DEFINITIONS);
    }

}