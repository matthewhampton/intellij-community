package com.intellij.xml.impl;

import com.intellij.xml.actions.ValidateXmlActionHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.codeInsight.daemon.Validator;
import com.intellij.psi.xml.*;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.SourceTreeToPsiMap;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.List;
import java.util.LinkedList;
import java.util.Iterator;
import java.lang.ref.WeakReference;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;

import org.xml.sax.SAXParseException;

/**
 * Created by IntelliJ IDEA.
 * User: maxim
 * Date: 21.01.2005
 * Time: 0:07:51
 * To change this template use File | Settings | File Templates.
 */
public class ExternalDocumentValidator {
  private static final Logger LOG = Logger.getInstance("#com.intellij.xml.impl.ExternalDocumentValidator");
  private static final Key<ExternalDocumentValidator> validatorInstanceKey = Key.create("validatorInstance");
  private ValidateXmlActionHandler myHandler;
  private Validator.ValidationHost myHost;

  private long myTimeStamp;
  private VirtualFile myFile;

  class ValidationInfo {
    PsiElement element;
    String message;
    int type;
  }

  private WeakReference<List<ValidationInfo>> myInfos; // last jaxp validation result

  private void runJaxpValidation(final XmlElement element, Validator.ValidationHost host) {
    VirtualFile virtualFile = element.getContainingFile().getVirtualFile();

    if (myFile == virtualFile &&
        virtualFile != null &&
        myTimeStamp == virtualFile.getTimeStamp() &&
        myInfos!=null &&
        myInfos.get()!=null // we have validated before
        ) {
      addAllInfos(host,myInfos.get());
      return;
    }

    PsiFile containingFile = element.getContainingFile();
    if (myHandler==null)  myHandler = new ValidateXmlActionHandler(false);
    final Project project = element.getProject();

    final Document document = PsiDocumentManager.getInstance(project).getDocument(containingFile);
    if (document==null) return;
    final List<ValidationInfo> results = new LinkedList<ValidationInfo>();

    myHost = new Validator.ValidationHost() {
      public void addMessage(PsiElement context, String message, int type) {
        final ValidationInfo o = new ValidationInfo();

        results.add(o);
        o.element = context;
        o.message = message;
        o.type = type;
      }
    };

    myHandler.setErrorReporter(myHandler.new ErrorReporter() {
      public boolean filterValidationException(Exception ex) {
        super.filterValidationException(ex);
        if (ex instanceof FileNotFoundException ||
            ex instanceof MalformedURLException
            ) {
          // do not log problems caused by malformed and/or ignored external resources
          return true;
        }
        return false;
      }

      public void processError(final SAXParseException e, final boolean warning) {
        try {
          ApplicationManager.getApplication().runReadAction(new Runnable() {
            public void run() {
              if (document.getLineCount() <= e.getLineNumber() || e.getLineNumber() <= 0) {
                return;
              }

              int offset = Math.max(0, document.getLineStartOffset(e.getLineNumber() - 1) + e.getColumnNumber() - 2);
              if (offset >= document.getTextLength()) return;
              PsiElement currentElement = PsiDocumentManager.getInstance(project).getPsiFile(document).findElementAt(offset);
              PsiElement originalElement = currentElement;
              final String elementText = currentElement.getText();

              if (elementText.equals("</")) {
                currentElement = currentElement.getNextSibling();
              }
              else if (elementText.equals(">") || elementText.equals("=")) {
                currentElement = currentElement.getPrevSibling();
              }

              // Cannot find the declaration of element
              String localizedMessage = e.getLocalizedMessage();
              localizedMessage = localizedMessage.substring(localizedMessage.indexOf(':') + 1).trim();

              if (localizedMessage.startsWith("Cannot find the declaration of element") ||
                  localizedMessage.startsWith("Element") ||
                  localizedMessage.startsWith("Document root element") ||
                  localizedMessage.startsWith("The content of element type")
                  ) {
                addProblemToTagName(currentElement, originalElement, localizedMessage, warning);
                //return;
              } else if (localizedMessage.startsWith("Value ")) {
                addProblemToTagName(currentElement, originalElement, localizedMessage, warning);
                return;
              } else if (localizedMessage.startsWith("Attribute ")) {
                currentElement = PsiTreeUtil.getParentOfType(currentElement,XmlAttribute.class,false);
                final int messagePrefixLength = "Attribute ".length();

                if (currentElement==null && localizedMessage.charAt(messagePrefixLength) == '"') {
                  // extract the attribute name from message and get it from tag!
                  final int nextQuoteIndex = localizedMessage.indexOf('"', messagePrefixLength + 1);
                  String attrName = (nextQuoteIndex!=-1)?localizedMessage.substring(messagePrefixLength + 1, nextQuoteIndex):null;

                  XmlTag parent = PsiTreeUtil.getParentOfType(originalElement,XmlTag.class);
                  currentElement = parent.getAttribute(attrName,null);

                  if (currentElement!=null) {
                    currentElement = SourceTreeToPsiMap.treeElementToPsi(
                      XmlChildRole.ATTRIBUTE_NAME_FINDER.findChild(
                        SourceTreeToPsiMap.psiElementToTree(currentElement)
                      )
                    );
                  }
                }

                if (currentElement!=null) {
                  assertValidElement(currentElement, originalElement,localizedMessage);
                  myHost.addMessage(currentElement,localizedMessage,warning ? Validator.ValidationHost.WARNING:Validator.ValidationHost.ERROR);
                } else {
                  addProblemToTagName(originalElement, originalElement, localizedMessage, warning);
                }
                return;
              } else {
                currentElement = getNodeForMessage(currentElement);
                assertValidElement(currentElement, originalElement,localizedMessage);
                if (currentElement!=null) {
                  myHost.addMessage(currentElement,localizedMessage,warning ? Validator.ValidationHost.WARNING:Validator.ValidationHost.ERROR);
                }
              }
            }
          });
        }
        catch (Exception ex) {
          if (ex instanceof ProcessCanceledException) throw (ProcessCanceledException)ex;
          LOG.error(ex);
        }
      }

    });

    myHandler.doValidate(project, element.getContainingFile());

    myFile = containingFile.getVirtualFile();
    myTimeStamp = myFile == null ? 0 : myFile.getTimeStamp();
    myInfos = new WeakReference<List<ValidationInfo>>(results);

    addAllInfos(host,results);
  }

  private XmlElement getNodeForMessage(final PsiElement currentElement) {
    XmlElement parentOfType = PsiTreeUtil.getParentOfType(currentElement, XmlTag.class, false);
    if (parentOfType==null) {
      parentOfType = PsiTreeUtil.getParentOfType(currentElement, XmlProcessingInstruction.class, false);
    }
    return parentOfType;
  }

  private void addAllInfos(Validator.ValidationHost host,List<ValidationInfo> highlightInfos) {
    for (Iterator<ValidationInfo> iterator = highlightInfos.iterator(); iterator.hasNext();) {
      ValidationInfo info = iterator.next();
      host.addMessage(info.element,info.message, info.type);
    }
  }

  private PsiElement addProblemToTagName(PsiElement currentElement,
                                     final PsiElement originalElement,
                                     final String localizedMessage,
                                     final boolean warning) {
    currentElement = PsiTreeUtil.getParentOfType(currentElement,XmlTag.class,false);
    if (currentElement==null) {
      currentElement = PsiTreeUtil.getParentOfType(originalElement,XmlElementDecl.class,false);
    }
    assertValidElement(currentElement, originalElement,localizedMessage);

    if (currentElement!=null) {
      myHost.addMessage(currentElement,localizedMessage,warning ? Validator.ValidationHost.WARNING:Validator.ValidationHost.ERROR);
    }

    return currentElement;
  }

  private static void assertValidElement(PsiElement currentElement, PsiElement originalElement, String message) {
    if (currentElement==null) {
      XmlTag tag = PsiTreeUtil.getParentOfType(originalElement, XmlTag.class);
      LOG.assertTrue(
        false,
        "The validator message:"+ message+ " is bound to null node,\n" +
        "initial element:"+originalElement.getText()+",\n"+
        "parent:" + originalElement.getParent()+",\n" +
        "tag:" + (tag != null? tag.getText():"null") + ",\n" +
        "offset in tag: " + (originalElement.getTextOffset() - ((tag!=null)?tag.getTextOffset():0))
      );
    }
  }

  public static void doValidation(final PsiElement context, final Validator.ValidationHost host) {
    final PsiFile containingFile = context.getContainingFile();
    if (containingFile==null || containingFile.getFileType()!=StdFileTypes.XML) return;
    final Project project = context.getProject();
    ExternalDocumentValidator validator = project.getUserData(validatorInstanceKey);

    if(validator==null) {
      validator = new ExternalDocumentValidator();
      project.putUserData(validatorInstanceKey,validator);
    }

    validator.runJaxpValidation((XmlElement)context,host);
  }

  
}
