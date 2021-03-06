/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.cvsSupport2.actions;

import com.intellij.cvsSupport2.actions.cvsContext.CvsContext;
import com.intellij.openapi.vcs.actions.VcsContext;
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler;
import com.intellij.cvsSupport2.cvshandlers.CvsHandler;
import com.intellij.cvsSupport2.cvsoperations.cvsWatch.WatcherInfo;
import com.intellij.cvsSupport2.cvsoperations.cvsWatch.WatchersOperation;
import com.intellij.cvsSupport2.cvsoperations.cvsWatch.ui.WatchersPanel;
import com.intellij.cvsSupport2.ui.CvsTabbedWindow;
import com.intellij.cvsSupport2.util.CvsVfsUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.CvsBundle;

import java.util.List;

/**
 * author: lesya
 */
public class ViewWatchersAction extends AbstractActionFromEditGroup {
  private WatchersOperation myWatchersOperation;

  protected String getTitle(VcsContext context) {
    return CvsBundle.getViewEditorsOperationName();
  }

  protected CvsHandler getCvsHandler(CvsContext context) {
    myWatchersOperation = new WatchersOperation(context.getSelectedFiles());
    return new CommandCvsHandler(com.intellij.CvsBundle.message("operation.name.veiw.watchers"), myWatchersOperation);
  }

  protected void onActionPerformed(CvsContext context,
                                   CvsTabbedWindow tabbedWindow,
                                   boolean successfully,
                                   CvsHandler handler) {
    super.onActionPerformed(context, tabbedWindow, successfully, handler);
    if (successfully) {
      List<WatcherInfo> watchers = myWatchersOperation.getWatchers();
      String filePath = CvsVfsUtil.getFileFor(context.getSelectedFile()).getAbsolutePath();
      if (watchers.isEmpty()) {
        Messages.showMessageDialog(CvsBundle.message("message.error.no.watchers.for.file", filePath), CvsBundle.message("message.error.no.watchers.for.file.title"), Messages.getInformationIcon());
      }
      else {
        tabbedWindow.addTab(CvsBundle.message("message.watchers.for.file", filePath), new WatchersPanel(watchers), true, true, true, true,
                            null, "cvs.watchers");
        tabbedWindow.ensureVisible(context.getProject());
      }
    }
  }

}
