package org.jdownloader.gui.views.downloads.action;

import java.awt.event.ActionEvent;
import java.util.HashSet;
import java.util.List;

import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;

import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.swing.dialog.Dialog;
import org.appwork.utils.swing.dialog.DialogCanceledException;
import org.appwork.utils.swing.dialog.DialogClosedException;
import org.appwork.utils.swing.dialog.ProgressDialog;
import org.appwork.utils.swing.dialog.ProgressDialog.ProgressGetter;
import org.jdownloader.controlling.contextmenu.CustomizableTableContextAppAction;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.gui.views.SelectionInfo;
import org.jdownloader.gui.views.components.packagetable.LinkTreeUtils;
import org.jdownloader.images.NewTheme;

public class OpenInBrowserAction extends CustomizableTableContextAppAction<FilePackage, DownloadLink> {

    private static final long                        serialVersionUID = 7911375550836173693L;
    private SelectionInfo<FilePackage, DownloadLink> selection;

    public OpenInBrowserAction() {

        setIconKey("browse");
        setName(_GUI._.gui_table_contextmenu_browselink());
    }

    @Override
    public void requestUpdate(Object requestor) {
        super.requestUpdate(requestor);
        selection = getSelection();

        if (!CrossSystem.isOpenBrowserSupported()) {

            setEnabled(false);
            return;
        }
        if (!hasSelection(selection)) {

            setEnabled(false);
            return;
        }
        List<DownloadLink> links = selection.getChildren();
        if (links.size() > 50) {

            setEnabled(false);
            return;
        }
        for (DownloadLink link : links) {
            if (link.getLinkType() == DownloadLink.LINKTYPE_NORMAL || link.gotBrowserUrl()) {

                setEnabled(true);
                return;
            }
        }
        setEnabled(false);
    }

    public void actionPerformed(ActionEvent e) {
        if (!isEnabled()) {
            return;
        }

        if (!hasSelection(selection)) {
            return;
        }
        final List<DownloadLink> links = selection.getChildren();
        new Thread("OpenInBrowserAction") {
            public void run() {
                final HashSet<String> urls = LinkTreeUtils.getURLs(links, true);
                if (links.size() < 5) {
                    for (String url : urls) {
                        CrossSystem.openURLOrShowMessage(url);
                    }
                    return;
                }
                ProgressDialog pg = new ProgressDialog(new ProgressGetter() {
                    private int total = -1;
                    private int current;

                    @Override
                    public void run() throws Exception {
                        total = urls.size();
                        current = 0;
                        for (String url : urls) {
                            CrossSystem.openURLOrShowMessage(url);
                            current++;
                            Thread.sleep(500);

                        }
                    }

                    @Override
                    public String getString() {
                        return current + "/" + total;
                    }

                    @Override
                    public int getProgress() {
                        if (total == 0) {
                            return -1;
                        }
                        int ret = (current * 100) / total;

                        return ret;
                    }

                    @Override
                    public String getLabelString() {
                        return null;
                    }
                }, 0, _GUI._.OpenInBrowserAction_actionPerformed_open_in_browser__multi(), _GUI._.OpenInBrowserAction_actionPerformed_open_in_browser__multi_msg(urls.size()), NewTheme.I().getIcon(IconKey.ICON_BROWSE, 32), null, null);

                try {
                    Dialog.getInstance().showDialog(pg);
                } catch (DialogClosedException e) {
                    e.printStackTrace();
                } catch (DialogCanceledException e) {
                    e.printStackTrace();
                }
            }
        }.start();

    }
}