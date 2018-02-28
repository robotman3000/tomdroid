package sbs20.filenotes.storage;

import android.app.Activity;
import android.content.Context;
import android.preference.PreferenceManager;
import android.text.InputType;

import com.dropbox.core.*;
import com.dropbox.core.android.Auth;
import com.dropbox.core.android.AuthActivity;
import com.dropbox.core.v1.DbxEntry;
import com.dropbox.core.v2.*;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.ListFolderBuilder;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.files.UploadUploader;
import com.dropbox.core.v2.files.WriteMode;

import org.tomdroid.Note;
import org.tomdroid.R;
import org.tomdroid.sync.dropbox.NoteManifest;
import org.tomdroid.util.TLog;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import sbs20.filenotes.model.Logger;
import sbs20.filenotes.model.Settings;

public class DropboxService {

    private final Context context;
    private Settings settings;
    private static String APP_KEY = "yh5ubtwey9lw8g9";
    private final static String TAG = "DropboxNotesService";

    private static DbxClientV2 client;

    private NoteManifest manifest = null;
    private List<String> deletedNotes = new ArrayList<>();
    private List<Note> updatedNotes = new ArrayList<>();

    public DropboxService(Activity context) {
        this.context = context;
        this.settings = new Settings(PreferenceManager.getDefaultSharedPreferences(context));
    }

    private String appKey() {
        return APP_KEY;
    }

    private String clientId() {
        return APP_KEY;
    }

    private String locale() {
        return "en-us";
    }

    private String getAuthenticationToken() {
        String accessToken = this.settings.getDropboxAccessToken();

        if (accessToken == null) {
            accessToken = Auth.getOAuth2Token();
            if (accessToken != null) {
                this.settings.setDropboxAccessToken(accessToken);
            }
        }

        return accessToken;
    }

    private DbxClientV2 getClient() {
        if (client == null) {
            String accessToken = this.getAuthenticationToken();
            if (accessToken != null && accessToken.length() > 0) {
                DbxRequestConfig config = new DbxRequestConfig(clientId(), locale());
                client = new DbxClientV2(config, accessToken);
            }
        }

        // It's still possible that client is null...
        return client;
    }

    public boolean isAuthenticated() {
        return this.getClient() != null;
    }

    public void login(Context context) {
        Logger.info(this, "login()");
        if (!this.isAuthenticated()) {
            Logger.verbose(this, "login():!Authenticated");
            Auth.startOAuth2Authentication(context, appKey());
        }else {
            Logger.info(this, "login(): Already Complete");
        }
    }

    public void logout() {
        client = null;
        AuthActivity.result = null;
        this.settings.clearDropboxAccessToken();
        Logger.info(this, "logout()");
    }

    public List<FileMetadata> files() throws IOException {
        Logger.info(this, "files():Start");

        List<FileMetadata> files = new ArrayList<>();

        if (this.isAuthenticated()) {
            Logger.verbose(this, "files():Authenticated");

            try {
                ListFolderBuilder listFolderBuilder = client.files().listFolderBuilder("");
                ListFolderResult result = listFolderBuilder.withRecursive(true).start();
                while (true) {

                    for (Metadata m : result.getEntries()) {
                        if (m instanceof FileMetadata) {
                            FileMetadata f = (FileMetadata) m;
                            files.add(f);
                        }
                    }

                    if (result.getHasMore()) {
                        result = client.files().listFolderContinue(result.getCursor());
                    } else {
                        break;
                    }
                }
            } catch (DbxException dbxException) {
                throw new IOException(dbxException);
            }

        } else {
            Logger.verbose(this, "files():!Authenticated");
        }

        return files;
    }

    public void move(DbxEntry.File file, String desiredPath) throws Exception {
        Logger.info(this, "move():Start");
        FileMetadata remoteFile = (FileMetadata) client.files().getMetadata(file.path);

        if (remoteFile != null) {
            client.files().move(remoteFile.getPathLower(), desiredPath);
            Logger.verbose(this, "move():done");
        }
    }

    public void upload(String path, InputStream inputStream) throws Exception {
        if(path.startsWith("/")){
            path = path.substring(1);
        }
        Logger.info(this, "upload():Start");

        if (path != null /*TODO: and is a valid path*/) {
            String remoteFolderPath = this.settings.getRemoteStoragePath();

            UploadUploader result = client.files().uploadBuilder("/" + path)
                    .withMode(WriteMode.OVERWRITE)
                    .start();

            result.uploadAndFinish(inputStream);
            Logger.verbose(this, "upload():done");
        }
    }

    /**
     *
     * @param file
     * @return the File representing the downloaded file
     * @throws Exception
     */
    public File download(FileMetadata file) throws Exception {
        Logger.info(this, "download():Start");
        FileMetadata remoteFile = (FileMetadata) file;

        if (remoteFile != null) {

            // Local file
            File localFile = new File(context.getCacheDir(), file.getPathLower());

            File parent = localFile.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Couldn't create dir: " + parent);
            }
            localFile.createNewFile();

            OutputStream outputStream = new FileOutputStream(localFile, false);

            DbxDownloader<FileMetadata> result = client.files()
                    .downloadBuilder(remoteFile.getPathLower())
                    .withRev(remoteFile.getRev())
                    .start();

            result.download(outputStream);

            // We will attempt to set the last modified time. This MIGHT help replication
            // and it certainly looks better. However, it doesn't seem to work reliably with
            // external storage. On the plus side it seems fine for internal storage.
            // http://stackoverflow.com/questions/18677438/android-set-last-modified-time-for-the-file
            //localFile.setLastModified(remoteFile.getServerModified().getTime());

            Logger.verbose(this, "download():done");
            return localFile;
        }
        return null;
    }

    public void delete(DbxEntry.File file) throws DbxException {
        Logger.info(this, "delete():Start");
        FileMetadata remoteFile = (FileMetadata) client.files().getMetadata(file.path);

        if (remoteFile != null) {
            client.files().delete(remoteFile.getPathLower());
            Logger.verbose(this, "delete():done");
        }
    }

    public List<String> getChildDirectoryPaths(String path) throws DbxException {
        List<String> dirs = new ArrayList<>();
        Logger.info(this, "getChildDirectoryPaths(" + path + ")");

        if (this.isAuthenticated()) {

            if (!path.equals(this.getRootDirectoryPath())) {
                // We need to add the parent... to do that...
                String parent = path.substring(0, path.lastIndexOf("/"));
                dirs.add(parent);
            }

            ListFolderResult result = client.files().listFolder(path);

            for (Metadata entry : result.getEntries()) {
                if (entry instanceof Metadata) {
                    FolderMetadata folder = (FolderMetadata) entry;
                    dirs.add(folder.getPathLower());
                    Logger.info(this, "getChildDirectoryPaths() - " + folder.getPathLower());
                }
            }

            Collections.sort(dirs);
        }

        return dirs;
    }

    public String getRootDirectoryPath() {
        return "";
    }

    public void createDirectory(String path) throws Exception {
        Logger.info(this, "createDirectory(" + path + ")");
        if (this.isAuthenticated()) {
            client.files().createFolderV2(path);
            Logger.verbose(this, "createDirectory():done");
        }
    }

    public void fetchManifest(){
        // Mock fetch
        /*Random rand = new Random();
        manifest = new NoteManifest(rand.nextInt(100));
        int noteCnt = rand.nextInt(100);
        for (int index = 0; index < noteCnt; index++){
            manifest.setNoteRevision(UUID.randomUUID().toString(), rand.nextInt(10)); // Creates if non-existant
        }*/
        TLog.v(TAG, "fetchManifest: {0}", manifest);
        boolean manifestFound = false;
        try {
            List<FileMetadata> files = files();
            for (FileMetadata file : files) {
                if (file.getPathLower().equals("/manifest.xml")) {
                    TLog.v(TAG, "manifest found");

                    File manifestFile = download(file);
                    DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory
                            .newInstance();
                    DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
                    Document document = documentBuilder.parse(manifestFile);
                    this.manifest = new NoteManifest(document);
                    manifestFound = true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (!manifestFound){
            this.manifest = new NoteManifest(0);
        }
    }

    public List<File> fetchNotes() {
        ArrayList<File> notes = new ArrayList<>();
        for (String guid : manifest.getNotes().keySet()){
            File noteFile = fetchNote(guid.toString());
            if(noteFile != null) {
                TLog.v(TAG, "fetchNotes: Note file was null! {0}", guid);
                notes.add(noteFile);
            }
        }
        TLog.v(TAG, "fetchNotes: {0}", notes);
        return notes;
    }

    public long getRevision() {
        TLog.v(TAG, "getRevision: {0}", manifest.getRevision());
        return manifest.getRevision();
    }

    public void setRevision(long revision) {
        TLog.v(TAG, "setRevision: {0}", revision);
        manifest.setRevision(revision);
    }

    public File fetchNote(String guid) {
        TLog.v(TAG, "fetchNote(): {0}", guid);
        // Mock fetch
        /*Note note = new Note();
        note.setTitle(guid);
        return note;*/

        if(manifest.getNoteRevision(guid) > -1) {
            List<FileMetadata> files = null;
            try {
                files = files();
            } catch (IOException e) {
                e.printStackTrace();
            }
            for (FileMetadata file : files) {
                if (file.getPathLower().equals("/0/" + manifest.getNoteRevision(guid) + "/" + guid + ".note")) {
                    try {
                        TLog.v(TAG, "note {0} found", guid);
                        File noteFile = download(file);
                        TLog.d(TAG, "parsing note. filename: {0}", file.getName());
                        return noteFile;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return null;
    }

    public void deleteNote(String guid) {
        TLog.v(TAG, "deleteNote(): {0}", guid);
        manifest.deleteNote(guid);
        deletedNotes.add(guid);
    }

    public void uploadNote(Note note) {
        manifest.incNoteRevision(note.getGuid());
        updatedNotes.add(note);
        TLog.v(TAG, "uploadNote(): {0}", note);
    }

    public void performSync() {
        TLog.v(TAG, "performSync()");
        //DO: upload new notes
        for (Note note : updatedNotes){
            String xmlOutput = note.getXmlFileString();
            ByteArrayInputStream str = new ByteArrayInputStream(xmlOutput.getBytes());
            try {
                manifest.incNoteRevision(note.getGuid());
                TLog.v(TAG, "uploading note {0} to {1}", note.getGuid(), "0/" + manifest.getNoteRevision(note.getGuid()) + "/" + note.getGuid() + ".note");
                upload("0/" + manifest.getNoteRevision(note.getGuid()) + "/" + note.getGuid() + ".note", str);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        //DO: delete notes
        for (String guid : deletedNotes){
            //TODO: Actually delete the matching note from the server
        }

        //DO: update manifest
        ByteArrayInputStream manifestStream = new ByteArrayInputStream(manifest.toXmlString().getBytes());
        try {
            // Note: the dual copies of the manifest is how the desktop tomboy client does things
            upload("0/" + manifest.getRevision() + "/manifest.xml", manifestStream);
            manifestStream.reset();
            upload("manifest.xml", manifestStream);
        } catch (Exception e) {
            e.printStackTrace();
        }

        deletedNotes.clear();
        updatedNotes.clear();
    }
}
