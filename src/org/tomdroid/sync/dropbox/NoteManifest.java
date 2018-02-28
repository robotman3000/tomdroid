package org.tomdroid.sync.dropbox;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Created by robotman3000 on 2/24/18.
 */

public class NoteManifest {

    private Map<String, Long> notes = new HashMap<>();
    private long revision;

    public NoteManifest(long revision){
        this.revision = revision;
    }

    public NoteManifest(Document document) {
        NamedNodeMap nodes = document.getElementsByTagName("sync").item(0).getAttributes();
        setRevision(
                Long.valueOf(
                nodes.getNamedItem("revision")
                        .getNodeValue()));
        NodeList noteNodes = document.getElementsByTagName("note");
        for (int index = 0; index < noteNodes.getLength(); index++){
            String id = noteNodes.item(index).getAttributes().getNamedItem("id").getNodeValue();
            String revStr = noteNodes.item(index).getAttributes().getNamedItem("rev").getNodeValue();
            long rev = Long.valueOf(revStr);

            //TODO: Validate the parsed values
            notes.put(id, rev);
        }
    }

    public Map<String, Long> getNotes() {
        return notes;
    }

    public void incNoteRevision(String guid){
        notes.put(guid, getRevision());
    }

    public long getNoteRevision(String guid){
        Long rev = notes.get(guid);
        return rev != null ? rev : -1L;
    }

    public void setNoteRevision(String uuid, long i) {
        if(i < 0){
            notes.remove(uuid);
        } else {
            notes.put(uuid, i);
        }
    }

    public void deleteNote(String guid){
        setNoteRevision(guid, -1);
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public String toXmlString() {
        String xmlStr = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<sync revision=\"" + getRevision() +"\" server-id=\"dbf8153c-0c85-4864-b363-17dd7de54884\">\n"; //TODO: Include serverID
        for(Map.Entry<String, Long> ent : notes.entrySet()){
            xmlStr += "  <note id=\"" + ent.getKey() + "\" rev=\"" + ent.getValue() + "\" />\n";
        }
        xmlStr += "</sync>";
        return xmlStr;
    }
}
