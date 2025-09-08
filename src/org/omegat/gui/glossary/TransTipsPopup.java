/**************************************************************************
 OmegaT - Computer Assisted Translation (CAT) tool
          with fuzzy matching, translation memory, keyword search,
          glossaries, and translation leveraging into updated projects.

 Copyright (C) 2010 Wildrich Fourie, Alex Buloichik
               2011 Didier Briel
               2013 Aaron Madlon-Kay
               Home page: https://www.omegat.org/
               Support center: https://omegat.org/support

 This file is part of OmegaT.

 OmegaT is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 OmegaT is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <https://www.gnu.org/licenses/>.
 **************************************************************************/

package org.omegat.gui.glossary;

import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Set;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.text.JTextComponent;

import org.omegat.core.Core;
import org.omegat.gui.dialogs.CreateGlossaryEntry;
import org.omegat.gui.editor.IPopupMenuConstructor;
import org.omegat.gui.editor.SegmentBuilder;
import org.omegat.util.HttpConnectionUtils;
import org.omegat.util.Log;
import org.omegat.util.OStrings;
import org.omegat.util.Preferences;
import org.omegat.util.StringUtil;
import org.omegat.util.Token;
import org.omegat.util.gui.StaticUIUtils;
import org.openide.awt.Mnemonics;

/**
 * Popup for TransTips processing.
 *
 * @author W. Fourie
 * @author Alex Buloichik (alex73mail@gmail.com)
 * @author Didier Briel
 * @author Aaron Madlon-Kay
 * @author Hanqin Chen
 */
public class TransTipsPopup implements IPopupMenuConstructor {

    public void addItems(final JPopupMenu menu, JTextComponent comp, final int mousepos,
            boolean isInActiveEntry, boolean isInActiveTranslation, SegmentBuilder sb) {
        if (!Core.getEditor().getSettings().isMarkGlossaryMatches()) {
            return;
        }

        if (!isInActiveEntry || isInActiveTranslation) {
            return;
        }

        // is mouse in active entry's source?
        final int startSource = sb.getStartSourcePosition();
        int len = sb.getSourceText().length();
        if (mousepos < startSource || mousepos > startSource + len) {
            return;
        }

        Set<String> added = new HashSet<>();
        for (GlossaryEntry ge : GlossaryTextArea.nowEntries) {
            for (Token[] toks : Core.getGlossaryManager().searchSourceMatchTokens(sb.getSourceTextEntry(),
                    ge)) {
                for (Token tok : toks) {
                    // is it on found word?
                    if (startSource + tok.getOffset() <= mousepos
                            && mousepos <= startSource + tok.getOffset() + tok.getLength()) {
                        // Create the MenuItems

                        String[] locTerms = ge.getLocTerms(true);
                        for (int i = 0; i < locTerms.length; i++) {
                            String s = locTerms[i];
                            if (!added.contains(s)) {
                                JMenu entry = new JMenu(s);

                                // Tooltip may overlap with the popup menu
                                // Replaced it with a menu item
                                String comment = ge.getCommentIndex(i);
                                if (comment != null && !comment.isEmpty()) {
                                    JMenuItem subComment = new JMenuItem(comment);
                                    subComment.setEnabled(false);
                                    entry.add(subComment);
                                    entry.addSeparator();
                                }

                                // Insert entry
                                JMenuItem subInsert = new JMenuItem(Mnemonics.removeMnemonics(
                                        OStrings.getString("GUI_GLOSSARYWINDOW_ENTRY_insert")));
                                subInsert.addActionListener(e -> Core.getEditor().insertText(s));
                                entry.add(subInsert);

                                final int I = i;
                                JMenuItem subDelete = new JMenuItem(Mnemonics.removeMnemonics(
                                        OStrings.getString("GUI_GLOSSARYWINDOW_ENTRY_delete")));
                                String search = ge.getSrcText() + '\t' + locTerms[i];
                                subDelete.addActionListener(e -> {
                                    // ask to delete an entry
                                    int res = JOptionPane.showConfirmDialog(
                                            Core.getMainWindow().getApplicationFrame(),
                                            OStrings.getString("MW_DELETE_ENTRY_QUESTION"),
                                            OStrings.getString("MW_DELETE_ENTRY_TITLE"),
                                            JOptionPane.YES_NO_OPTION);
                                    if (res == JOptionPane.YES_OPTION) {
                                        try {
                                            editLine(ge.getOriginIndex(I), search, "");
                                        } catch (IOException ex) {
                                            Log.log(ex);
                                        }
                                    }
                                });
                                entry.add(subDelete);

                                // Reuse the create glossary entry dialog as the
                                // edit entry dialog
                                JMenuItem subEdit = new JMenuItem(Mnemonics.removeMnemonics(
                                        OStrings.getString("GUI_GLOSSARYWINDOW_ENTRY_edit")));
                                subEdit.addActionListener(e -> editGlossaryDialog(
                                        Core.getMainWindow().getApplicationFrame(), ge, I));
                                entry.add(subEdit);

                                added.add(s);
                                menu.add(entry);
                            }
                        }
                    }
                }
            }
        }
        menu.addSeparator();
    }

    public static void editLine(String glossPathStr, String search, String replace) throws IOException {
        // absolute path of the glossary file
        Path gFilePath = Paths.get(glossPathStr);
        // name of the glossary file with extension
        Path gFileName = gFilePath.getFileName();
        // absolute path of which the glossary file is in
        Path gFileDir = gFilePath.getParent();

        // Create a temp file
        Path tempPath = Files.createTempFile(gFileDir, null, null);

        // Read the original file and write to the temp file
        File gFile = new File(glossPathStr);
        File tmpFile = tempPath.toFile();
        String encoding = GlossaryReaderTSV.getFileEncoding(gFile);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(gFile), encoding));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(tmpFile), encoding))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(search)) {
                    writer.write(line);
                    writer.newLine();
                } else {
                    // empty string means delete this line
                    if (!replace.isEmpty()) {
                        writer.write(replace);
                        writer.newLine();
                    }
                }
            }
        }

        // Move the original file to the 'old' folder with .old extension
        Path oldFolderPath = gFileDir.resolve("old");
        Path oldFilePath = oldFolderPath.resolve(gFileName + ".old");

        if (!Files.exists(oldFolderPath)) {
            Files.createDirectory(oldFolderPath);
        }
        Files.move(gFilePath, oldFilePath, StandardCopyOption.REPLACE_EXISTING);

        // Rename the temp file to the original glossary file name
        Files.move(tempPath, gFilePath, StandardCopyOption.REPLACE_EXISTING);
    }

    private CreateGlossaryEntry singleDialog;

    public void editGlossaryDialog(final Frame parent, GlossaryEntry glossEntry, int entryIndex) {
        CreateGlossaryEntry d = singleDialog;
        if (d != null) {
            d.requestFocus();
            return;
        }

        String gFilePathStr = glossEntry.getOriginIndex(entryIndex);

        // init glossary entry dialog with given GlossaryEntry object
        final CreateGlossaryEntry dialog = new CreateGlossaryEntry(parent);
        String str = dialog.getGlossaryFileText().getText();
        str = MessageFormat.format(str, gFilePathStr);
        dialog.getGlossaryFileText().setText(str);
        dialog.getSourceText().setText(glossEntry.getSrcText());
        dialog.getTargetText().setText(glossEntry.getLocIndex(entryIndex));
        dialog.getCommentText().setText(glossEntry.getCommentIndex(entryIndex));

        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                super.windowClosed(e);
                singleDialog = null;
                if (dialog.getReturnStatus() == CreateGlossaryEntry.RET_OK) {
                    String src = StringUtil.normalizeUnicode(dialog.getSourceText().getText()).trim();
                    String loc = StringUtil.normalizeUnicode(dialog.getTargetText().getText()).trim();
                    String com = HttpConnectionUtils.encodeHttpURLs(
                            StringUtil.normalizeUnicode(dialog.getCommentText().getText()).trim());
                    if (!StringUtil.isEmpty(src) && !StringUtil.isEmpty(loc)) {
                        try {
                            String search = glossEntry.getSrcText() + '\t'
                                    + glossEntry.getLocIndex(entryIndex);
                            String replace = src + '\t' + loc + '\t' + com;
                            editLine(gFilePathStr, search, replace);
                        } catch (IOException ex) {
                            Log.log(ex);
                        }
                    }
                }
            }
        });

        StaticUIUtils.persistGeometry(dialog, Preferences.CREATE_GLOSSARY_GEOMETRY_PREFIX);
        dialog.setVisible(true);
        singleDialog = dialog;
    }
}
