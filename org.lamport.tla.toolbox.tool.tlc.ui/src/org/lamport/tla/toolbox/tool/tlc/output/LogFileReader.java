package org.lamport.tla.toolbox.tool.tlc.output;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.editors.text.FileDocumentProvider;
import org.eclipse.ui.part.FileEditorInput;
import org.lamport.tla.toolbox.tool.tlc.output.source.ITLCOutputSource;
import org.lamport.tla.toolbox.tool.tlc.output.source.TagBasedTLCOutputIncrementalParser;
import org.lamport.tla.toolbox.tool.tlc.ui.TLCUIActivator;

/**
 * Reads the log file and parses the content
 * @author Simon Zambrovski
 * @version $Id$
 */
public class LogFileReader
{
    private TagBasedTLCOutputIncrementalParser parser;
    private IFile logFile;

    public LogFileReader(String name, IFile logFile, boolean isTraceExplorerLogFile)
    {
        this.logFile = logFile;
        this.parser = new TagBasedTLCOutputIncrementalParser(name, ITLCOutputSource.PRIO_LOW, isTraceExplorerLogFile);
    }

    /**
     * Reads the contents
     */
    public void read()
    {
        FileEditorInput fileEditorInput = new FileEditorInput(logFile);
        FileDocumentProvider fileDocumentProvider = new FileDocumentProvider();
        try
        {
            fileDocumentProvider.connect(fileEditorInput);
            IDocument document = fileDocumentProvider.getDocument(fileEditorInput);
            /*
             * When the output file is somewhat large (several hundred KB)
             * the toolbox hangs due to the parser inefficiently handling multiple lines at once.
             * If the entire document representing that file
             * is passed to the parser at once. We can send in pieces of it
             * to solve this problem.
             * 
             * We send in one line at a time. Note that IDocument lines
             * are 0-based.
             */
            for (int lineNum = 0; lineNum < document.getNumberOfLines(); lineNum++)
            {
                String line = document.get(document.getLineOffset(lineNum), document.getLineLength(lineNum));
                this.parser.addIncrement(line);
            }
            this.parser.done();
        } catch (CoreException e)
        {
            TLCUIActivator.getDefault().logError("Error accessing the TLC log file contents", e);
        } catch (BadLocationException e)
        {
            TLCUIActivator.getDefault().logError("Error positioning in the TLC log file", e);
        } finally
        {
            /*
             * The document provider is not needed. Always disconnect it to avoid a memory leak.
             * 
             * Keeping it connected only seems to provide synchronization of
             * the document with file changes. That is not necessary in this context.
             */
            fileDocumentProvider.disconnect(fileEditorInput);
        }

    }

    public ITLCOutputSource getSource()
    {
        return this.parser.getSource();
    }

}
