/*
 * Tomdroid
 * Tomboy on Android
 * http://www.launchpad.net/tomdroid
 * 
 * Copyright 2008, 2009, 2010 Olivier Bilodeau <olivier@bottomlesspit.org>
 * Copyright 2009, Benoit Garret <benoit.garret_launchpad@gadz.org>
 * 
 * This file is part of Tomdroid.
 * 
 * Tomdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Tomdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Tomdroid.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.tomdroid.ui;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.tomdroid.Note;
import org.tomdroid.NoteManager;
import org.tomdroid.R;
import org.tomdroid.ui.actionbar.ActionBarActivity;
import org.tomdroid.util.LinkifyPhone;
import org.tomdroid.util.NoteContentBuilder;
import org.tomdroid.util.Preferences;
import org.tomdroid.util.Send;
import org.tomdroid.util.NoteXMLContentBuilder;
import org.tomdroid.util.TLog;
import org.tomdroid.xml.NoteContentHandler;
import org.xml.sax.InputSource;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnClickListener;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.format.Time;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.util.Linkify;
import android.text.util.Linkify.TransformFilter;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SlidingDrawer;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

// TODO this class is starting to smell
public class EditNote extends ActionBarActivity {
	
	// UI elements
	private EditText title;
	private EditText content;
	private SlidingDrawer formatBar;
	
	// Model objects
	private Note note;
	private SpannableStringBuilder noteContent;
	
	// Logging info
	private static final String TAG = "EditNote";
	
	private Uri uri;
	public static final String CALLED_FROM_SHORTCUT_EXTRA = "org.tomdroid.CALLED_FROM_SHORTCUT";
    public static final String SHORTCUT_NAME = "org.tomdroid.SHORTCUT_NAME";
    
	// rich text variables
	
	int styleStart = -1, cursorLoc = 0;
    private int sselectionStart;
	private int sselectionEnd;
    private float size = 1.0f;
	private boolean xmlOn = false;
	
	// check whether text has been changed yet
	private boolean textChanged = false;
	// discard changes -> not will not be saved
	private boolean discardChanges = false;
	
	// TODO extract methods in here
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.note_edit);
		
		content = (EditText) findViewById(R.id.content);
		title = (EditText) findViewById(R.id.title);
		
		formatBar = (SlidingDrawer) findViewById(R.id.formatBar);

		content.setOnFocusChangeListener(new OnFocusChangeListener() {

		    public void onFocusChange(View v, boolean hasFocus) {
		    	if(hasFocus && !xmlOn) {
		    		formatBar.setVisibility(View.VISIBLE);
		    	}
		    	else {
		    		formatBar.setVisibility(View.GONE);
		    	}
		    }
		});
		
        uri = getIntent().getData();
	}

	private void handleNoteUri(final Uri uri) {// We were triggered by an Intent URI
        TLog.d(TAG, "EditNote started: Intent-filter triggered.");

        // TODO validate the good action?
        // intent.getAction()

        // TODO verify that getNote is doing the proper validation
        note = NoteManager.getNote(this, uri);

        if(note != null) {
			title.setText((CharSequence) note.getTitle());
            noteContent = note.getNoteContent(noteContentHandler);
        } else {
            TLog.d(TAG, "The note {0} doesn't exist", uri);
            showNoteNotFoundDialog(uri);
        }
    }

	private Handler noteContentHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {

			//parsed ok - show
			if(msg.what == NoteContentBuilder.PARSE_OK) {
				showNote(false);
				
				// add format bar listeners here
				
				addFormatListeners();

			//parsed not ok - error
			} else if(msg.what == NoteContentBuilder.PARSE_ERROR) {

				new AlertDialog.Builder(EditNote.this)
					.setMessage(getString(R.string.messageErrorNoteParsing))
					.setTitle(getString(R.string.error))
					.setNeutralButton(getString(R.string.btnOk), new OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							dialog.dismiss();
							showNote(true);
						}})
					.show();
        	}
		}
	};
	
    private void showNoteNotFoundDialog(final Uri uri) {
    	final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(getString(R.string.messageNoteNotFound))
                .setTitle(getString(R.string.titleNoteNotFound))
                .setNeutralButton(getString(R.string.btnOk), new OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        finish();
                    }
                });
        builder.show();
    }
    
    @Override
    protected void onPause() {
    	if (uri != null) {
        	if(!discardChanges && textChanged) // changed and not discarding changes
       			saveNote();
        }
    	super.onPause();
    }

    @Override
    protected void onDestroy() {
		if(note.getTitle().length() == 0 && note.getXmlContent().length() == 0 && !textChanged) // if the note is empty, e.g. new
				NoteManager.deleteNote(this, note);
    	super.onDestroy();
    }
    
	@Override
	public void onResume(){
		TLog.v(TAG, "resume edit note");
		super.onResume();

        if (uri == null) {
			TLog.d(TAG, "The Intent's data was null.");
            showNoteNotFoundDialog(uri);
        } else handleNoteUri(uri);

		updateTextAttributes();
	}
	
	private void updateTextAttributes() {
		float baseSize = Float.parseFloat(Preferences.getString(Preferences.Key.BASE_TEXT_SIZE));
		content.setTextSize(baseSize);
		title.setTextSize(baseSize*1.3f);

		title.setTextColor(Color.BLUE);
		title.setPaintFlags(title.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
		title.setBackgroundColor(0xffffffff);

		content.setBackgroundColor(0xffffffff);
		content.setTextColor(Color.DKGRAY);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.edit_note, menu);

        // Calling super after populating the menu is necessary here to ensure that the
        // action bar helpers have a chance to handle this event.
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
	        case android.R.id.home:
	        	// app icon in action bar clicked; go home
                Intent intent = new Intent(this, Tomdroid.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            	return true;
			case R.id.menuPrefs:
				startActivity(new Intent(this, PreferencesActivity.class));
				return true;
			case R.id.edit_note_send:
					(new Send(this, note)).send();
					return true;
			case R.id.edit_note_save:
				saveNote();
				return true;
			case R.id.edit_note_discard:
				discardNoteContent();
				return true;
/*			case R.id.edit_note_xml:
            	if(!xmlOn) {
            		item.setTitle(getString(R.string.text));
            		item.setIcon(R.drawable.text);
            		xmlOn = true;
        			SpannableStringBuilder newNoteContent = (SpannableStringBuilder) content.getText();

        			// store changed note content
        			String newXmlContent = new NoteXMLContentBuilder().setCaller(noteXMLWriteHandler).setInputSource(newNoteContent).build();
        			// Since 0.5 EditNote expects the redundant title being removed from the note content, but we still may need this for debugging:
        			//note.setXmlContent("<note-content version=\"0.1\">"+note.getTitle()+"\n\n"+newXmlContent+"</note-content>");
        			TLog.d(TAG, "new xml content: {0}", newXmlContent);
        			note.setXmlContent(newXmlContent);
            		formatBarShell.setVisibility(View.GONE);
            		content.setText(note.getXmlContent());
            	}
            	else {
            		item.setTitle(getString(R.string.xml));
            		item.setIcon(R.drawable.xml);
            		xmlOn = false;
            		updateNoteContent(true);  // update based on xml that we are switching FROM
            		if(content.isFocused())
            			formatBarShell.setVisibility(View.VISIBLE);
            	}
				return true;*/
		}
		return super.onOptionsItemSelected(item);
	}
	
	
	private void showNote(boolean xml) {
		if(xml) {

			formatBar.setVisibility(View.GONE);
			
			content.setText(note.getXmlContent());
			xmlOn = true;
			return;
		}

		// show the note (spannable makes the TextView able to output styled text)
		content.setText(noteContent, TextView.BufferType.SPANNABLE);
		
		// add links to stuff that is understood by Android except phone numbers because it's too aggressive
		// TODO this is SLOWWWW!!!!
		Linkify.addLinks(content, Linkify.EMAIL_ADDRESSES|Linkify.WEB_URLS|Linkify.MAP_ADDRESSES);
		
		// Custom phone number linkifier (fixes lp:512204)
		Linkify.addLinks(content, LinkifyPhone.PHONE_PATTERN, "tel:", LinkifyPhone.sPhoneNumberMatchFilter, Linkify.sPhoneNumberTransformFilter);
		
		// This will create a link every time a note title is found in the text.
		// The pattern contains a very dumb (title1)|(title2) escaped correctly
		// Then we transform the url from the note name to the note id to avoid characters that mess up with the URI (ex: ?)
		Pattern pattern = buildNoteLinkifyPattern();
		
		if(pattern != null)
			Linkify.addLinks(content,
							 buildNoteLinkifyPattern(),
							 Tomdroid.CONTENT_URI+"/",
							 null,
							 noteTitleTransformFilter);
	}
	
	private Handler noteXMLParseHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {
			
			//parsed ok - show
			if(msg.what == NoteContentBuilder.PARSE_OK) {
				showNote(false);

			//parsed not ok - error
			} else if(msg.what == NoteContentBuilder.PARSE_ERROR) {
				
				// TODO put this String in a translatable resource
				new AlertDialog.Builder(EditNote.this)
					.setMessage("The requested note could not be parsed. If you see this error " +
								" and you are able to replicate it, please file a bug!")
					.setTitle("Error")
					.setNeutralButton("Ok", new OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							dialog.dismiss();
							showNote(true);
						}})
					.show();
        	}
		}
	};

	private Handler noteXMLWriteHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {
			
			//parsed ok - do nothing
			if(msg.what == NoteXMLContentBuilder.PARSE_OK) {
			//parsed not ok - error
			} else if(msg.what == NoteXMLContentBuilder.PARSE_ERROR) {
				
				// TODO put this String in a translatable resource
				new AlertDialog.Builder(EditNote.this)
					.setMessage("The requested note could not be parsed. If you see this error " +
								" and you are able to replicate it, please file a bug!")
					.setTitle("Error")
					.setNeutralButton("Ok", new OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							dialog.dismiss();
							finish();
						}})
					.show();
        	}
		}
	};

	/**
	 * Builds a regular expression pattern that will match any of the note title currently in the collection.
	 * Useful for the Linkify to create the links to the notes.
	 * @return regexp pattern
	 */
	private Pattern buildNoteLinkifyPattern()  {
		
		StringBuilder sb = new StringBuilder();
		Cursor cursor = NoteManager.getTitles(this);
		
		// cursor must not be null and must return more than 0 entry 
		if (!(cursor == null || cursor.getCount() == 0)) {
			
			String title;
			
			cursor.moveToFirst();
			
			do {
				title = cursor.getString(cursor.getColumnIndexOrThrow(Note.TITLE));
				if(title.length() == 0)
					continue;
				// Pattern.quote() here make sure that special characters in the note's title are properly escaped 
				sb.append("("+Pattern.quote(title)+")|");
				
			} while (cursor.moveToNext());

			// if only empty titles, return
			if (sb.length() == 0)
				return null;
			
			// get rid of the last | that is not needed (I know, its ugly.. better idea?)
			String pt = sb.substring(0, sb.length()-1);

			// return a compiled match pattern
			return Pattern.compile(pt);
			
		} else {
			
			// TODO send an error to the user
			TLog.d(TAG, "Cursor returned null or 0 notes");
		}
		
		return null;
	}
	
	// custom transform filter that takes the note's title part of the URI and translate it into the note id
	// this was done to avoid problems with invalid characters in URI (ex: ? is the query separator but could be in a note title)
	private TransformFilter noteTitleTransformFilter = new TransformFilter() {

		public String transformUrl(Matcher m, String str) {

			int id = NoteManager.getNoteId(EditNote.this, str);
			
			// return something like content://org.tomdroid.notes/notes/3
			return Tomdroid.CONTENT_URI.toString()+"/"+id;
		}  
	};

	private boolean updateNoteContent(boolean xml) {

		SpannableStringBuilder newNoteContent = new SpannableStringBuilder();
		// TODO: probably should remove the whole XML viewing function - I don't think there is any need for it except debugging
		if(xml) {
			// parse XML
			String xmlContent = "<note-content version=\"1.0\">"+this.content.getText().toString()+"</note-content>";
			String subjectName = this.title.getText().toString();
	        InputSource noteContentIs = new InputSource(new StringReader(xmlContent));
			try {
				// Parsing
		    	// XML 
		    	// Get a SAXParser from the SAXPArserFactory
		        SAXParserFactory spf = SAXParserFactory.newInstance();

		        // trashing the namespaces but keep prefixes (since we don't have the xml header)
		        spf.setFeature("http://xml.org/sax/features/namespaces", false);
		        spf.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
		        SAXParser sp = spf.newSAXParser();

		        sp.parse(noteContentIs, new NoteContentHandler(newNoteContent));
			} catch (Exception e) {
				e.printStackTrace();
				// TODO handle error in a more granular way
				TLog.e(TAG, "There was an error parsing the note {0}", subjectName);
				return false;
			}
			
		}
		else
			newNoteContent = (SpannableStringBuilder) this.content.getText();

		// store changed note content
		String newXmlContent = new NoteXMLContentBuilder().setCaller(noteXMLWriteHandler).setInputSource(newNoteContent).build();
		
		// Since 0.5 EditNote expects the redundant title being removed from the note content, but we still may need this for debugging:
		//note.setXmlContent("<note-content version=\"0.1\">"+note.getTitle()+"\n\n"+newXmlContent+"</note-content>");
		note.setXmlContent(newXmlContent);
		noteContent = note.getNoteContent(noteXMLWriteHandler);
		return true;
	}
	
	private void saveNote() {
		TLog.v(TAG, "saving note");
		
		boolean updated = updateNoteContent(xmlOn);
		if(!updated) {
			Toast.makeText(this, getString(R.string.messageErrorParsingXML), Toast.LENGTH_SHORT).show();
			return;
		}
		
		String validTitle = NoteManager.validateNoteTitle(this, title.getText().toString(), note.getGuid()); 
		title.setText(validTitle);
		note.setTitle(validTitle);

		Time now = new Time();
		now.setToNow();
		String time = now.format3339(false);
		note.setLastChangeDate(time);
		NoteManager.putNote( this, note);

		textChanged = false;

		Toast.makeText(this, getString(R.string.messageNoteSaved), Toast.LENGTH_SHORT).show();
		TLog.v(TAG, "note saved");
	}

	private void discardNoteContent() {
		new AlertDialog.Builder(EditNote.this)
			.setMessage(getString(R.string.messageDiscardChanges))
			.setTitle(getString(R.string.titleDiscardChanges))
			.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
			            public void onClick(DialogInterface dialog, int which) {
			            	discardChanges = true;
			            	dialog.dismiss();
							finish();
			            }
			        })
			        .setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
			            public void onClick(DialogInterface dialog, int which) {
							dialog.dismiss();
			            }
				})
			.show();
	}

	private void addFormatListeners()
	{
		
		final ToggleButton boldButton = (ToggleButton)findViewById(R.id.bold);
		
		boldButton.setOnClickListener(new Button.OnClickListener() {

			public void onClick(View v) {
		    	
            	int selectionStart = content.getSelectionStart();
            	
            	styleStart = selectionStart;
            	
            	int selectionEnd = content.getSelectionEnd();
            	
            	if (selectionStart > selectionEnd){
            		int temp = selectionEnd;
            		selectionEnd = selectionStart;
            		selectionStart = temp;
            	}
            	
            	if (selectionEnd > selectionStart)
            	{
            		Spannable str = content.getText();
            		StyleSpan[] ss = str.getSpans(selectionStart, selectionEnd, StyleSpan.class);
            		
            		boolean exists = false;
            		for (int i = 0; i < ss.length; i++) {
            			if (ss[i].getStyle() == android.graphics.Typeface.BOLD){
            				str.removeSpan(ss[i]);
            				exists = true;
            			}
                    }
            		
            		if (!exists){
            			str.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), selectionStart, selectionEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            		}
        			textChanged = true;
        			updateNoteContent(xmlOn);
            		boldButton.setChecked(false);
            	}
            	else
            		cursorLoc = selectionStart;
            }
		});
		
		final ToggleButton italicButton = (ToggleButton)findViewById(R.id.italic);
		
		italicButton.setOnClickListener(new Button.OnClickListener() {
            public void onClick(View v) {
            	            	
            	int selectionStart = content.getSelectionStart();
            	
            	styleStart = selectionStart;
            	
            	int selectionEnd = content.getSelectionEnd();
            	
            	if (selectionStart > selectionEnd){
            		int temp = selectionEnd;
            		selectionEnd = selectionStart;
            		selectionStart = temp;
            	}
            	
            	if (selectionEnd > selectionStart)
            	{
            		Spannable str = content.getText();
            		StyleSpan[] ss = str.getSpans(selectionStart, selectionEnd, StyleSpan.class);
            		
            		boolean exists = false;
            		for (int i = 0; i < ss.length; i++) {
            			if (ss[i].getStyle() == android.graphics.Typeface.ITALIC){
            				str.removeSpan(ss[i]);
            				exists = true;
            			}
                    }
            		
            		if (!exists){
            			str.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), selectionStart, selectionEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            		}
            		
        			textChanged = true;
            		updateNoteContent(xmlOn);
	          		italicButton.setChecked(false);
            	}
            	else
            		cursorLoc = selectionStart;
            }
		});
		
		final ToggleButton strikeoutButton = (ToggleButton) findViewById(R.id.strike);   
        
		strikeoutButton.setOnClickListener(new Button.OnClickListener() {
            public void onClick(View v) {
            	 
            	int selectionStart = content.getSelectionStart();
            	
            	styleStart = selectionStart;
            	
            	int selectionEnd = content.getSelectionEnd();
            	
            	if (selectionStart > selectionEnd){
            		int temp = selectionEnd;
            		selectionEnd = selectionStart;
            		selectionStart = temp;
            	}
            	
            	if (selectionEnd > selectionStart)
            	{
            		Spannable str = content.getText();
            		StrikethroughSpan[] ss = str.getSpans(selectionStart, selectionEnd, StrikethroughSpan.class);
            		
            		boolean exists = false;
            		for (int i = 0; i < ss.length; i++) {
            				str.removeSpan(ss[i]);
            				exists = true;
                    }
            		
            		if (!exists){
            			str.setSpan(new StrikethroughSpan(), selectionStart, selectionEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            		}
        			textChanged = true;
            		updateNoteContent(xmlOn);
            		strikeoutButton.setChecked(false);
            	}
            	else
            		cursorLoc = selectionStart;
            }
        });
		
		final ToggleButton highButton = (ToggleButton)findViewById(R.id.highlight);
		
		highButton.setOnClickListener(new Button.OnClickListener() {
            public void onClick(View v) {
            	            	
            	int selectionStart = content.getSelectionStart();
            	
            	styleStart = selectionStart;
            	
            	int selectionEnd = content.getSelectionEnd();
            	
            	if (selectionStart > selectionEnd){
            		int temp = selectionEnd;
            		selectionEnd = selectionStart;
            		selectionStart = temp;
            	}
            	
            	if (selectionEnd > selectionStart)
            	{
            		Spannable str = content.getText();
            		BackgroundColorSpan[] ss = str.getSpans(selectionStart, selectionEnd, BackgroundColorSpan.class);
            		
            		boolean exists = false;
            		for (int i = 0; i < ss.length; i++) {
        				str.removeSpan(ss[i]);
        				exists = true;
                    }
            		
            		if (!exists){
            			str.setSpan(new BackgroundColorSpan(Note.NOTE_HIGHLIGHT_COLOR), selectionStart, selectionEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            		}
            		
        			textChanged = true;
        			updateNoteContent(xmlOn);
            		highButton.setChecked(false);
            	}
            	else
            		cursorLoc = selectionStart;
            }
		});
		
		final ToggleButton monoButton = (ToggleButton)findViewById(R.id.mono);
		
		monoButton.setOnClickListener(new Button.OnClickListener() {
            public void onClick(View v) {
            	            	
            	int selectionStart = content.getSelectionStart();
            	
            	styleStart = selectionStart;
            	
            	int selectionEnd = content.getSelectionEnd();
            	
            	if (selectionStart > selectionEnd){
            		int temp = selectionEnd;
            		selectionEnd = selectionStart;
            		selectionStart = temp;
            	}
            	
            	if (selectionEnd > selectionStart)
            	{
            		Spannable str = content.getText();
            		TypefaceSpan[] ss = str.getSpans(selectionStart, selectionEnd, TypefaceSpan.class);
            		
            		boolean exists = false;
            		for (int i = 0; i < ss.length; i++) {
            			if (ss[i].getFamily()==Note.NOTE_MONOSPACE_TYPEFACE){
            				str.removeSpan(ss[i]);
            				exists = true;
            			}
                    }
            		
            		if (!exists){
            			str.setSpan(new TypefaceSpan(Note.NOTE_MONOSPACE_TYPEFACE), selectionStart, selectionEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            		}
            		
        			textChanged = true;
        			updateNoteContent(xmlOn);
            		monoButton.setChecked(false);
            	}
            	else
            		cursorLoc = selectionStart;
            }
		});
        
        content.addTextChangedListener(new TextWatcher() { 
            public void afterTextChanged(Editable s) {
            	
                // set text as changed to force auto save if preferred
            	textChanged = true;
 
            	//add style as the user types if a toggle button is enabled
            	
            	int position = Selection.getSelectionStart(content.getText());
            	
        		if (position < 0){
        			position = 0;
        		}
            	
        		if (position > 0){
        			
        			if (styleStart > position || position > (cursorLoc + 1)){
						//user changed cursor location, reset
						if (position - cursorLoc > 1){
							//user pasted text
							styleStart = cursorLoc;
						}
						else{
							styleStart = position - 1;
						}
					}
        			
                	if (boldButton.isChecked()){  
                		StyleSpan[] ss = s.getSpans(styleStart, position, StyleSpan.class);

                		for (int i = 0; i < ss.length; i++) {
            				s.removeSpan(ss[i]);
                        }
                		s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), styleStart, position, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                	}
                	if (italicButton.isChecked()){
                		StyleSpan[] ss = s.getSpans(styleStart, position, StyleSpan.class);
                		
                		for (int i = 0; i < ss.length; i++) {
                			if (ss[i].getStyle() == android.graphics.Typeface.ITALIC){
                    			if (ss[i].getStyle() == android.graphics.Typeface.ITALIC){
                    				s.removeSpan(ss[i]);
                    			}
                			}
                        }
                		s.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), styleStart, position, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                	}
                	if (strikeoutButton.isChecked()){
                		StrikethroughSpan[] ss = s.getSpans(styleStart, position, StrikethroughSpan.class);
                		
                		for (int i = 0; i < ss.length; i++) {
            				s.removeSpan(ss[i]);
                        }
            			s.setSpan(new StrikethroughSpan(), styleStart, position, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                	}
                	if (highButton.isChecked()){
                		BackgroundColorSpan[] ss = s.getSpans(styleStart, position, BackgroundColorSpan.class);
                		
                		for (int i = 0; i < ss.length; i++) {
            				s.removeSpan(ss[i]);
                        }
            			s.setSpan(new BackgroundColorSpan(Note.NOTE_HIGHLIGHT_COLOR), styleStart, position, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                	}
                	if (monoButton.isChecked()){
                		TypefaceSpan[] ss = s.getSpans(styleStart, position, TypefaceSpan.class);
                		
                		for (int i = 0; i < ss.length; i++) {
                			if (ss[i].getFamily()==Note.NOTE_MONOSPACE_TYPEFACE){
                				s.removeSpan(ss[i]);
                			}
                        }
            			s.setSpan(new TypefaceSpan(Note.NOTE_MONOSPACE_TYPEFACE), styleStart, position, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                	}
                	if (size != 1.0f){
                		RelativeSizeSpan[] ss = s.getSpans(styleStart, position, RelativeSizeSpan.class);
                		
                		for (int i = 0; i < ss.length; i++) {
                			s.removeSpan(ss[i]);
                		}
                		s.setSpan(new RelativeSizeSpan(size), styleStart, position, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                	}
        		}
        		
        		cursorLoc = Selection.getSelectionStart(content.getText());
            } 
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { 
                    //unused
            } 
            public void onTextChanged(CharSequence s, int start, int before, int count) { 
                    //unused
            } 
        });

        // set text as changed to force auto save if preferred
        
        title.addTextChangedListener(new TextWatcher() { 
            public void afterTextChanged(Editable s) {
            	textChanged = true;
            } 
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { 
                    //unused
            } 
            public void onTextChanged(CharSequence s, int start, int before, int count) { 
                    //unused
            } 
        });
        
		final ToggleButton sizeButton = (ToggleButton)findViewById(R.id.size);
		
		sizeButton.setOnClickListener(new Button.OnClickListener() {

			public void onClick(View v) {
				sselectionStart = content.getSelectionStart();
		    	sselectionEnd = content.getSelectionEnd();
            	showSizeDialog();
            }
		});
	}
	
	private void showSizeDialog() {
		final CharSequence[] items = {getString(R.string.small), getString(R.string.normal), getString(R.string.large), getString(R.string.huge)};

		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.messageSelectSize);
		builder.setSingleChoiceItems(items, -1, new DialogInterface.OnClickListener() {
		    public void onClick(DialogInterface dialog, int item) {
		        Toast.makeText(getApplicationContext(), items[item], Toast.LENGTH_SHORT).show();	
		        switch (item) {
	        		case 0: size = 0.8f; break;
	        		case 1: size = 1.0f; break;
	        		case 2: size = 1.5f; break;
	        		case 3: size = 1.8f; break;
				}
		        
		    	if (sselectionStart == sselectionEnd) {
		    		// there is no text selected -> start span here and elongate while typing
	            	styleStart = sselectionStart;
		    		cursorLoc = sselectionStart;
		    	} else {
		    		// there is some text selected, just change the size of this text
		    		changeSize();
		    	}
                dialog.dismiss();
		    }
		});
		builder.show();
	}

	public void changeSize() 
	{
        if (sselectionStart > sselectionEnd){
        	int temp = sselectionEnd;
        	sselectionEnd = sselectionStart;
        	sselectionStart = temp;
        }
        
    	if(sselectionStart < sselectionEnd)
    	{
        	Spannable str = content.getText();
        	
        	// get all the spans in the selected range
        	RelativeSizeSpan[] ss = str.getSpans(sselectionStart, sselectionEnd, RelativeSizeSpan.class);
        	
        	// check the position of the old span and the text size and decide how to rebuild the spans
    		for (int i = 0; i < ss.length; i++) {
    			int oldStart = str.getSpanStart(ss[i]);
    			int oldEnd = str.getSpanEnd(ss[i]);
    			float oldSize = ss[i].getSizeChange();
    			str.removeSpan(ss[i]);
				
    			if (oldStart < sselectionStart && sselectionEnd < oldEnd) {
    				// old span starts end ends outside selection
    				str.setSpan(new RelativeSizeSpan(oldSize), oldStart, sselectionStart, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            		str.setSpan(new RelativeSizeSpan(oldSize), sselectionEnd, oldEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    			} else if (oldStart < sselectionStart && oldEnd <= sselectionEnd){
    				// old span starts outside, ends inside the selection
            		str.setSpan(new RelativeSizeSpan(oldSize), oldStart, sselectionStart, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    			} else if (sselectionStart <= oldStart && sselectionEnd < oldEnd){
    				// old span starts inside, ends outside the selection
    				str.setSpan(new RelativeSizeSpan(oldSize), sselectionEnd, oldEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    			} else if (sselectionStart <= oldStart && oldEnd <= sselectionEnd) {
    				// old span was equal or within the selection -> just delete it and make the new one.
    			}
    	
            }
    		// generate the new span in the selected range
        	if(size != 1.0f) {
        		str.setSpan(new RelativeSizeSpan(size), sselectionStart, sselectionEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        	}
			textChanged = true;
			updateNoteContent(xmlOn);
			size = 1.0f;
    	}
    }	
}
