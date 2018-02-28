package org.tomdroid.sync.dropbox;

import android.app.Activity;
import android.database.Cursor;
import android.os.Handler;
import android.util.TimeFormatException;

import org.tomdroid.Note;
import org.tomdroid.NoteManager;
import org.tomdroid.R;
import org.tomdroid.sync.SyncService;
import org.tomdroid.sync.sd.NoteHandler;
import org.tomdroid.util.ErrorList;
import org.tomdroid.util.Preferences;
import org.tomdroid.util.TLog;
import org.tomdroid.util.Time;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import sbs20.filenotes.storage.DropboxService;

/**
 * Created by enims on 2/24/18.
 */

public class DropboxSyncService extends SyncService {

    private final static String TAG = "DropboxSyncService";
    private static Pattern note_content = Pattern.compile("<note-content[^>]+>(.*)<\\/note-content>", Pattern.CASE_INSENSITIVE+Pattern.DOTALL);
    private String lastGUID;
    private long latestRemoteRevision = -1;
    private long latestLocalRevision = -1;

    private DropboxService dbx;

    public DropboxSyncService(Activity activity, Handler handler) {
        super(activity, handler);
        this.dbx = new DropboxService(activity);
    }

    @Override
    public boolean needsServer() {
        return false;
    }

    @Override
    public boolean needsLogin() {
        return false;
    }

    @Override
    public boolean needsLocation() {
        return false;
    }

    @Override
    public boolean needsAuth() {
        return false;
    }

    @Override
    public String getName() {
        return "tomboy-dropbox";
    }

    @Override
    public int getDescriptionAsId() {
        return R.string.prefDropboxSync;
    }

    @Override
    protected void getNotesForSync(final boolean push) {
        // TODO: Make this only return the notes that have changed

        // We pull all notes from remote an give to prepareSyncableNotes();
        this.push = push;

        // start loading snowy notes
        setSyncProgress(0);
        this.lastGUID = null;

        TLog.v(TAG, "Loading Dropbox notes");


        syncInThread(new Runnable() {
            public void run() {
                try {
                    TLog.v(TAG, "Logging in to dropbox");
                    dbx.login(activity);

                    latestRemoteRevision = (int)Preferences.getLong(Preferences.Key.LATEST_SYNC_REVISION);

                    TLog.v(TAG, "Connecting to dropbox");
                    if(cancelled) {
                        doCancel();
                        return;
                    }

                    if (!dbx.isAuthenticated()) {
                        sendMessage(CONNECTING_FAILED);
                        setSyncProgress(100);
                        return;
                    }

                    setSyncProgress(30);

                    dbx.fetchManifest();
                    // get notes list without content, to check for revision

                    latestLocalRevision = (Long)Preferences.getLong(Preferences.Key.LATEST_SYNC_REVISION);

                    setSyncProgress(35);

                    latestRemoteRevision = dbx.getRevision();
                    sendMessage(LATEST_REVISION,(int)latestRemoteRevision,0);
                    TLog.d(TAG, "old latest sync revision: {0}, remote latest sync revision: {1}", latestLocalRevision, latestRemoteRevision);

                    Cursor newLocalNotes = NoteManager.getNewNotes(activity);

                    // same sync revision + no new local notes = no need to sync

                    if (latestRemoteRevision <= latestLocalRevision && newLocalNotes.getCount() == 0) {
                        TLog.v(TAG, "old sync revision on server, cancelling");
                        finishSync(true);
                        return;
                    }

                    // don't get notes if older revision - only pushing notes

                    if (push && latestRemoteRevision <= latestLocalRevision) {
                        TLog.v(TAG, "old sync revision on server, pushing new notes");
                        prepareSyncableNotes(newLocalNotes);
                        setSyncProgress(50);
                        return;
                    }

                    // get notes list with content to find changes

                    TLog.v(TAG, "Fetching notes from dropbox");
                    sendMessage(SYNC_CONNECTED);
                    if(cancelled) {
                        doCancel();
                        return;
                    }
                    latestRemoteRevision = dbx.getRevision();
                    sendMessage(LATEST_REVISION,(int)latestRemoteRevision,0);
                    List<File> remoteNotesFiles = dbx.fetchNotes();
                    ArrayList<Note> remoteNotes = new ArrayList<>();
                    for(File localRemoteNoteFile : remoteNotesFiles){
                        Note parsedNote = parseNote(localRemoteNoteFile, localRemoteNoteFile.getName().replace(".note", ""));
                        remoteNotes.add(parsedNote);
                    }
                    setSyncProgress(50);

                    TLog.v(TAG, "number of notes: {0}", remoteNotes.size());

                    if(cancelled) {
                        doCancel();
                        return;
                    }

                    // close cursor
                    newLocalNotes.close();
                    prepareSyncableNotes(remoteNotes);

                    if(cancelled) {
                        doCancel();
                        return;
                    }

                    setSyncProgress(100);
                    if (isSyncable())
                        finishSync(true);
                } catch (Exception e){
                    e.printStackTrace();
                    setSyncProgress(100);
                    SyncService.ERROR_MESSAGE = e.getMessage();
                    sendMessage(UNKNOWN_ERROR);
                }
            }
        });
    }

    @Override
    protected void pullNote(final String guid) {
        // Fetch one note from server and perform insertNote(); with it
        // start loading snowy notes

        TLog.v(TAG, "pulling remote note from dropbox");

        syncInThread(new Runnable() {

            public void run() {

                //try {
                    TLog.v(TAG, "contacting ");
                    dbx.login(activity);

                    //try {
                        File noteFile = dbx.fetchNote(guid);
                        if (noteFile != null) {
                            Note parsedNote = parseNote(noteFile, guid);
                            if(parsedNote != null) {
                                TLog.v(TAG, "Inserting Note: " + parsedNote);
                                insertNote(parsedNote);
                            } else {
                                TLog.v(TAG, "Failed to parse note: " + guid);
                            }
                        } else {
                            TLog.v(TAG, "No note exists: " + guid);
                        }

                    //} catch (JSONException e) {
                    //    TLog.e(TAG, e, "Problem parsing the server response");
                        //sendMessage(NOTE_PULL_ERROR,
                        //        ErrorList.createErrorWithContents(
                        //                "JSON parsing", "json", e, rawResponse));
                    //    return;
                    //}

                /*} catch (java.net.UnknownHostException e) {
                    TLog.e(TAG, "Internet connection not available");
                    sendMessage(NO_INTERNET);
                    return;
                }*/

                sendMessage(NOTE_PULLED);
            }
        });
    }

    public Note parseNote(File file, String guid) {
        Note note = new Note();
        final char[] buffer = new char[0x1000];
        note.setFileName(file.getAbsolutePath());
        // the note guid is not stored in the xml but in the filename
        note.setGuid(guid);

        // Try reading the file first
        String contents = "";
        try {
            contents = readFile(file, buffer);
        } catch (IOException e) {
            e.printStackTrace();
            TLog.w(TAG, "Something went wrong trying to read the note");
            sendMessage(PARSING_FAILED, ErrorList.createError(note, e));
            return null;
        }

        try {
            // Parsing
            // XML
            // Get a SAXParser from the SAXPArserFactory
            SAXParserFactory spf = SAXParserFactory.newInstance();
            SAXParser sp = spf.newSAXParser();

            // Get the XMLReader of the SAXParser we created
            XMLReader xr = sp.getXMLReader();

            // Create a new ContentHandler, send it this note to fill and apply it to the XML-Reader
            NoteHandler xmlHandler = new NoteHandler(note);
            xr.setContentHandler(xmlHandler);

            // Create the proper input source
            StringReader sr = new StringReader(contents);
            InputSource is = new InputSource(sr);

            TLog.d(TAG, "parsing note. filename: {0}", file.getName());
            xr.parse(is);

            // TODO wrap and throw a new exception here
        } catch (Exception e) {
            e.printStackTrace();
            if (e instanceof TimeFormatException)
                TLog.e(TAG, "Problem parsing the note's date and time");
            sendMessage(PARSING_FAILED, ErrorList.createErrorWithContents(note, e, contents));
            return null;
        }

        // FIXME here we are re-reading the whole note just to grab note-content out, there is probably a better way to do this (I'm talking to you xmlpull.org!)
        Matcher m = note_content.matcher(contents);
        if (m.find()) {
            note.setXmlContent(NoteManager.stripTitleFromContent(m.group(1), note.getTitle()));
        } else {
            TLog.w(TAG, "Something went wrong trying to grab the note-content out of a note");
            sendMessage(PARSING_FAILED, ErrorList.createErrorWithContents(note, "Something went wrong trying to grab the note-content out of a note", contents));
            return null;
        }
        return note;
    }

    private static String readFile(File file, char[] buffer) throws IOException {
        StringBuilder out = new StringBuilder();

        int read;
        Reader reader = new InputStreamReader(new FileInputStream(file), "UTF-8");

        do {
            read = reader.read(buffer, 0, buffer.length);
            if (read > 0) {
                out.append(buffer, 0, read);
            }
        }
        while (read >= 0);

        reader.close();
        return out.toString();
    }

    @Override
    public void finishSync(boolean refresh) {
        // Cleanup
        // delete leftover local notes
        NoteManager.purgeDeletedNotes(activity);

        Time now = new Time();
        now.setToNow();
        String nowString = now.formatTomboy();
        Preferences.putString(Preferences.Key.LATEST_SYNC_DATE, nowString);
        Preferences.putLong(Preferences.Key.LATEST_SYNC_REVISION, latestRemoteRevision);

        setSyncProgress(100);
        if (refresh)
            sendMessage(PARSING_COMPLETE);
    }

    @Override
    public void pushNotes(final ArrayList<Note> notes) {
        // This updates/creates and deletes notes on the remote
        // When finished call finishSync to clean up
        if(notes.size() == 0)
            return;
        if(cancelled) {
            doCancel();
            return;
        }

        final long newRevision = Preferences.getLong(Preferences.Key.LATEST_SYNC_REVISION)+1;

        syncInThread(new Runnable() {
            public void run() {
                TLog.v(TAG, "pushing {0} notes to dropbox, sending rev #{1}",notes.size(), newRevision);

                if(cancelled) {
                    doCancel();
                    return;
                }

                dbx.login(activity);
                //try {
                    TLog.v(TAG, "Updating manifest");

                    dbx.setRevision(newRevision);
                    for(Note note : notes) {
                        if(note.getTags().contains("system:deleted")) // deleted note
                            dbx.deleteNote(note.getGuid());
                        else { // changed note
                            dbx.uploadNote(note);
                        }
                    }

                    if(cancelled) {
                        doCancel();
                        return;
                    }

                    TLog.v(TAG, "pushing data to dropbox: {0}");
                    dbx.performSync();
                    dbx.fetchManifest();
                    latestRemoteRevision = dbx.getRevision();
                    sendMessage(LATEST_REVISION,(int)latestRemoteRevision,0);

                /*} catch (java.net.UnknownHostException e) {
                    TLog.e(TAG, "Internet connection not available");
                    sendMessage(NO_INTERNET);
                    return;
                }*/
                // success, finish sync
                finishSync(true);
            }

        });
    }

    @Override
    public void backupNotes() {
        //TODO: Implement Me!!!
    }

    @Override
    public void deleteAllNotes() {
        // Delete all notes on remote
        //TODO: Implement Me!!!
    }

    @Override
    protected void localSyncComplete() {
        Preferences.putLong(Preferences.Key.LATEST_SYNC_REVISION, latestRemoteRevision);
    }

}