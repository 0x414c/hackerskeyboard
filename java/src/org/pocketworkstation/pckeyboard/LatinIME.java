/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.pocketworkstation.pckeyboard;

import org.pocketworkstation.pckeyboard.LatinIMEUtil.RingCharBuffer;

import com.android.inputmethod.voice.FieldContext;
import com.android.inputmethod.voice.SettingsUtil;
import com.android.inputmethod.voice.VoiceInput;

import org.xmlpull.v1.XmlPullParserException;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.speech.SpeechRecognizer;
import android.text.ClipboardManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodService implements
        LatinKeyboardBaseView.OnKeyboardActionListener, VoiceInput.UiListener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "PCKeyboardIME";
    private static final boolean PERF_DEBUG = false;
    static final boolean DEBUG = false;
    static final boolean TRACE = false;
    static final boolean VOICE_INSTALLED = true;
    static final boolean ENABLE_VOICE_BUTTON = true;
    static Map<Integer, String> ESC_SEQUENCES;

    private static final String PREF_VIBRATE_ON = "vibrate_on";
    static final String PREF_VIBRATE_LEN = "vibrate_len";
    private static final String PREF_SOUND_ON = "sound_on";
    private static final String PREF_POPUP_ON = "popup_on";
    private static final String PREF_AUTO_CAP = "auto_cap";
    private static final String PREF_QUICK_FIXES = "quick_fixes";
    private static final String PREF_SHOW_SUGGESTIONS = "show_suggestions";
    private static final String PREF_AUTO_COMPLETE = "auto_complete";
    // private static final String PREF_BIGRAM_SUGGESTIONS =
    // "bigram_suggestion";
    private static final String PREF_VOICE_MODE = "voice_mode";

    // Whether or not the user has used voice input before (and thus, whether to
    // show the
    // first-run warning dialog or not).
    private static final String PREF_HAS_USED_VOICE_INPUT = "has_used_voice_input";

    // Whether or not the user has used voice input from an unsupported locale
    // UI before.
    // For example, the user has a Chinese UI but activates voice input.
    private static final String PREF_HAS_USED_VOICE_INPUT_UNSUPPORTED_LOCALE = "has_used_voice_input_unsupported_locale";

    // A list of locales which are supported by default for voice input, unless
    // we get a
    // different list from Gservices.
    public static final String DEFAULT_VOICE_INPUT_SUPPORTED_LOCALES = "en "
            + "en_US " + "en_GB " + "en_AU " + "en_CA " + "en_IE " + "en_IN "
            + "en_NZ " + "en_SG " + "en_ZA ";

    // The private IME option used to indicate that no microphone should be
    // shown for a
    // given text field. For instance this is specified by the search dialog
    // when the
    // dialog is already showing a voice search button.
    private static final String IME_OPTION_NO_MICROPHONE = "nm";

    public static final String PREF_SELECTED_LANGUAGES = "selected_languages";
    public static final String PREF_INPUT_LANGUAGE = "input_language";
    private static final String PREF_RECORRECTION_ENABLED = "recorrection_enabled";
    static final String PREF_FULLSCREEN_OVERRIDE = "fullscreen_override";
    static final String PREF_CONNECTBOT_TAB_HACK = "connectbot_tab_hack";
    static final String PREF_FULL_KEYBOARD_IN_PORTRAIT = "full_keyboard_in_portrait";
    static final String PREF_SUGGESTIONS_IN_LANDSCAPE = "suggestions_in_landscape";
    static final String PREF_HEIGHT_PORTRAIT = "settings_height_portrait";
    static final String PREF_HEIGHT_LANDSCAPE = "settings_height_landscape";
    static final String PREF_HINT_MODE = "pref_hint_mode";

    private static final int MSG_UPDATE_SUGGESTIONS = 0;
    private static final int MSG_START_TUTORIAL = 1;
    private static final int MSG_UPDATE_SHIFT_STATE = 2;
    private static final int MSG_VOICE_RESULTS = 3;
    private static final int MSG_UPDATE_OLD_SUGGESTIONS = 4;

    // How many continuous deletes at which to start deleting at a higher speed.
    private static final int DELETE_ACCELERATE_AT = 20;
    // Key events coming any faster than this are long-presses.
    private static final int QUICK_PRESS = 200;

    static final int KEYCODE_ENTER = '\n';
    static final int KEYCODE_SPACE = ' ';
    static final int KEYCODE_PERIOD = '.';

    // Contextual menu positions
    private static final int POS_METHOD = 0;
    private static final int POS_SETTINGS = 1;

    // private LatinKeyboardView mInputView;
    private LinearLayout mCandidateViewContainer;
    private CandidateView mCandidateView;
    private Suggest mSuggest;
    private CompletionInfo[] mCompletions;

    private AlertDialog mOptionsDialog;
    private AlertDialog mVoiceWarningDialog;

    /* package */KeyboardSwitcher mKeyboardSwitcher;

    private UserDictionary mUserDictionary;
    private UserBigramDictionary mUserBigramDictionary;
    private ContactsDictionary mContactsDictionary;
    private AutoDictionary mAutoDictionary;

    private Hints mHints;

    private Resources mResources;

    private String mInputLocale;
    private String mSystemLocale;
    private LanguageSwitcher mLanguageSwitcher;

    private StringBuilder mComposing = new StringBuilder();
    private WordComposer mWord = new WordComposer();
    private int mCommittedLength;
    private boolean mPredicting;
    private boolean mRecognizing;
    private boolean mAfterVoiceInput;
    private boolean mImmediatelyAfterVoiceInput;
    private boolean mShowingVoiceSuggestions;
    private boolean mVoiceInputHighlighted;
    private boolean mEnableVoiceButton;
    private CharSequence mBestWord;
    private boolean mPredictionOn;
    private boolean mCompletionOn;
    private boolean mHasDictionary;
    private boolean mAutoSpace;
    private boolean mJustAddedAutoSpace;
    private boolean mAutoCorrectEnabled;
    private boolean mReCorrectionEnabled;
    // Bigram Suggestion is disabled in this version.
    private final boolean mBigramSuggestionEnabled = false;
    private boolean mAutoCorrectOn;
    // TODO move this state variable outside LatinIME
    private boolean mCapsLock;
    private boolean mModShift;
    private boolean mModCtrl;
    private boolean mModAlt;
    private boolean mModFn;
    private boolean mPasswordText;
    private boolean mVibrateOn;
    private int mVibrateLen;
    private boolean mSoundOn;
    private boolean mPopupOn;
    private boolean mAutoCap;
    private boolean mQuickFixes;
    private boolean mHasUsedVoiceInput;
    private boolean mHasUsedVoiceInputUnsupportedLocale;
    private boolean mLocaleSupportedForVoiceInput;
    private boolean mShowSuggestions;
    private boolean mIsShowingHint;
    private boolean mConnectbotTabHack;
    private boolean mFullscreenOverride;
    private boolean mFullInPortrait;
    private boolean mSuggestionsInLandscape;
    private int mHeightPortrait;
    private int mHeightLandscape;
    private int mHintMode;
    private int mCorrectionMode;
    private boolean mEnableVoice = true;
    private boolean mVoiceOnPrimary;
    private int mOrientation;
    private List<CharSequence> mSuggestPuncList;
    // Keep track of the last selection range to decide if we need to show word
    // alternatives
    private int mLastSelectionStart;
    private int mLastSelectionEnd;

    // Input type is such that we should not auto-correct
    private boolean mInputTypeNoAutoCorrect;

    // Indicates whether the suggestion strip is to be on in landscape
    private boolean mJustAccepted;
    private CharSequence mJustRevertedSeparator;
    private int mDeleteCount;
    private long mLastKeyTime;

    // Modifier keys state
    private ModifierKeyState mShiftKeyState = new ModifierKeyState();
    private ModifierKeyState mSymbolKeyState = new ModifierKeyState();
    private ModifierKeyState mCtrlKeyState = new ModifierKeyState();
    private ModifierKeyState mAltKeyState = new ModifierKeyState();
    private ModifierKeyState mFnKeyState = new ModifierKeyState();

    private Tutorial mTutorial;

    private AudioManager mAudioManager;
    // Align sound effect volume on music volume
    private final float FX_VOLUME = -1.0f;
    private boolean mSilentMode;

    /* package */String mWordSeparators;
    private String mSentenceSeparators;
    private String mSuggestPuncs;
    private VoiceInput mVoiceInput;
    private VoiceResults mVoiceResults = new VoiceResults();
    private boolean mConfigurationChanging;

    // Keeps track of most recently inserted text (multi-character key) for
    // reverting
    private CharSequence mEnteredText;
    private boolean mRefreshKeyboardRequired;

    // For each word, a list of potential replacements, usually from voice.
    private Map<String, List<CharSequence>> mWordToSuggestions = new HashMap<String, List<CharSequence>>();

    private ArrayList<WordAlternatives> mWordHistory = new ArrayList<WordAlternatives>();
    
    private PluginManager mPluginManager;

    private class VoiceResults {
        List<String> candidates;
        Map<String, List<CharSequence>> alternatives;
    }

    public abstract static class WordAlternatives {
        protected CharSequence mChosenWord;

        public WordAlternatives() {
            // Nothing
        }

        public WordAlternatives(CharSequence chosenWord) {
            mChosenWord = chosenWord;
        }

        @Override
        public int hashCode() {
            return mChosenWord.hashCode();
        }

        public abstract CharSequence getOriginalWord();

        public CharSequence getChosenWord() {
            return mChosenWord;
        }

        public abstract List<CharSequence> getAlternatives();
    }

    public class TypedWordAlternatives extends WordAlternatives {
        private WordComposer word;

        public TypedWordAlternatives() {
            // Nothing
        }

        public TypedWordAlternatives(CharSequence chosenWord,
                WordComposer wordComposer) {
            super(chosenWord);
            word = wordComposer;
        }

        @Override
        public CharSequence getOriginalWord() {
            return word.getTypedWord();
        }

        @Override
        public List<CharSequence> getAlternatives() {
            return getTypedSuggestions(word);
        }
    }

    /* package */Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_UPDATE_SUGGESTIONS:
                updateSuggestions();
                break;
            case MSG_UPDATE_OLD_SUGGESTIONS:
                setOldSuggestions();
                break;
            case MSG_START_TUTORIAL:
                if (mTutorial == null) {
                    if (mKeyboardSwitcher.getInputView().isShown()) {
                        mTutorial = new Tutorial(LatinIME.this,
                                mKeyboardSwitcher.getInputView());
                        mTutorial.start();
                    } else {
                        // Try again soon if the view is not yet showing
                        sendMessageDelayed(obtainMessage(MSG_START_TUTORIAL),
                                100);
                    }
                }
                break;
            case MSG_UPDATE_SHIFT_STATE:
                updateShiftKeyState(getCurrentInputEditorInfo());
                break;
            case MSG_VOICE_RESULTS:
                handleVoiceResults();
                break;
            }
        }
    };

    @Override
    public void onCreate() {
        Log.i("PCKeyboard", "onCreate(), os.version=" + System.getProperty("os.version"));
        LatinImeLogger.init(this);
        KeyboardSwitcher.init(this);
        super.onCreate();
        // setStatusIcon(R.drawable.ime_qwerty);
        mResources = getResources();
        final Configuration conf = mResources.getConfiguration();
        final SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(this);
        mLanguageSwitcher = new LanguageSwitcher(this);
        mLanguageSwitcher.loadLocales(prefs);
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        mKeyboardSwitcher.setLanguageSwitcher(mLanguageSwitcher);
        mSystemLocale = conf.locale.toString();
        mLanguageSwitcher.setSystemLocale(conf.locale);
        String inputLanguage = mLanguageSwitcher.getInputLanguage();
        if (inputLanguage == null) {
            inputLanguage = conf.locale.toString();
        }
        Resources res = getResources();
        mReCorrectionEnabled = prefs.getBoolean(PREF_RECORRECTION_ENABLED,
                res.getBoolean(R.bool.default_recorrection_enabled));
        mConnectbotTabHack = prefs.getBoolean(PREF_CONNECTBOT_TAB_HACK,
                res.getBoolean(R.bool.default_connectbot_tab_hack));
        mFullscreenOverride = prefs.getBoolean(PREF_FULLSCREEN_OVERRIDE,
                res.getBoolean(R.bool.default_fullscreen_override));
        mFullInPortrait = prefs.getBoolean(PREF_FULL_KEYBOARD_IN_PORTRAIT,
                res.getBoolean(R.bool.default_full_in_portrait));
        mSuggestionsInLandscape = prefs.getBoolean(PREF_SUGGESTIONS_IN_LANDSCAPE,
                res.getBoolean(R.bool.default_suggestions_in_landscape));
        mHeightPortrait = getHeight(prefs, PREF_HEIGHT_PORTRAIT, res.getString(R.string.default_height_portrait));
        mHeightLandscape = getHeight(prefs, PREF_HEIGHT_LANDSCAPE, res.getString(R.string.default_height_landscape));
        mHintMode = Integer.parseInt(prefs.getString(PREF_HINT_MODE, res.getString(R.string.default_hint_mode)));
        mKeyboardSwitcher.setFullKeyboardOptions(mFullInPortrait,
                mHeightPortrait, mHeightLandscape, mHintMode);

        PluginManager.getPluginDictionaries(getApplicationContext());
        mPluginManager = new PluginManager(this);
        final IntentFilter pFilter = new IntentFilter();
        pFilter.addDataScheme("package");
        pFilter.addAction("android.intent.action.PACKAGE_ADDED");
        pFilter.addAction("android.intent.action.PACKAGE_REPLACED");
        pFilter.addAction("android.intent.action.PACKAGE_REMOVED");
        registerReceiver(mPluginManager, pFilter);

        LatinIMEUtil.GCUtils.getInstance().reset();
        boolean tryGC = true;
        for (int i = 0; i < LatinIMEUtil.GCUtils.GC_TRY_LOOP_MAX && tryGC; ++i) {
            try {
                initSuggest(inputLanguage);
                tryGC = false;
            } catch (OutOfMemoryError e) {
                tryGC = LatinIMEUtil.GCUtils.getInstance().tryGCOrWait(
                        inputLanguage, e);
            }
        }

        mOrientation = conf.orientation;
        initSuggestPuncList();

        // register to receive ringer mode changes for silent mode
        IntentFilter filter = new IntentFilter(
                AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mReceiver, filter);
        if (VOICE_INSTALLED) {
            mVoiceInput = new VoiceInput(this, this);
            mHints = new Hints(this, new Hints.Display() {
                public void showHint(int viewResource) {
                    LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    View view = inflater.inflate(viewResource, null);
                    setCandidatesView(view);
                    setCandidatesViewShown(true);
                    mIsShowingHint = true;
                }
            });
        }
        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    private boolean isPortrait() {
        return (mOrientation == Configuration.ORIENTATION_PORTRAIT);
    }

    private boolean suggestionsDisabled() {
        return /*mKeyboardSwitcher.isFullMode() &&*/
                !mSuggestionsInLandscape &&
                !isPortrait();
    }

    /**
     * Loads a dictionary or multiple separated dictionary
     *
     * @return returns array of dictionary resource ids
     */
    /* package */static int[] getDictionary(Resources res) {
        String packageName = LatinIME.class.getPackage().getName();
        XmlResourceParser xrp = res.getXml(R.xml.dictionary);
        ArrayList<Integer> dictionaries = new ArrayList<Integer>();

        try {
            int current = xrp.getEventType();
            while (current != XmlResourceParser.END_DOCUMENT) {
                if (current == XmlResourceParser.START_TAG) {
                    String tag = xrp.getName();
                    if (tag != null) {
                        if (tag.equals("part")) {
                            String dictFileName = xrp.getAttributeValue(null,
                                    "name");
                            dictionaries.add(res.getIdentifier(dictFileName,
                                    "raw", packageName));
                        }
                    }
                }
                xrp.next();
                current = xrp.getEventType();
            }
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Dictionary XML parsing failure");
        } catch (IOException e) {
            Log.e(TAG, "Dictionary XML IOException");
        }

        int count = dictionaries.size();
        int[] dict = new int[count];
        for (int i = 0; i < count; i++) {
            dict[i] = dictionaries.get(i);
        }

        return dict;
    }

    private void initSuggest(String locale) {
        mInputLocale = locale;

        Resources orig = getResources();
        Configuration conf = orig.getConfiguration();
        Locale saveLocale = conf.locale;
        conf.locale = new Locale(locale);
        orig.updateConfiguration(conf, orig.getDisplayMetrics());
        if (mSuggest != null) {
            mSuggest.close();
        }
        SharedPreferences sp = PreferenceManager
                .getDefaultSharedPreferences(this);
        mQuickFixes = sp.getBoolean(PREF_QUICK_FIXES, getResources()
                .getBoolean(R.bool.default_quick_fixes));

        int[] dictionaries = getDictionary(orig);
        mSuggest = new Suggest(this, dictionaries);
        updateAutoTextEnabled(saveLocale);
        if (mUserDictionary != null)
            mUserDictionary.close();
        mUserDictionary = new UserDictionary(this, mInputLocale);
        if (mContactsDictionary == null) {
            mContactsDictionary = new ContactsDictionary(this,
                    Suggest.DIC_CONTACTS);
        }
        if (mAutoDictionary != null) {
            mAutoDictionary.close();
        }
        mAutoDictionary = new AutoDictionary(this, this, mInputLocale,
                Suggest.DIC_AUTO);
        if (mUserBigramDictionary != null) {
            mUserBigramDictionary.close();
        }
        mUserBigramDictionary = new UserBigramDictionary(this, this,
                mInputLocale, Suggest.DIC_USER);
        mSuggest.setUserBigramDictionary(mUserBigramDictionary);
        mSuggest.setUserDictionary(mUserDictionary);
        mSuggest.setContactsDictionary(mContactsDictionary);
        mSuggest.setAutoDictionary(mAutoDictionary);
        updateCorrectionMode();
        mWordSeparators = mResources.getString(R.string.word_separators);
        mSentenceSeparators = mResources
                .getString(R.string.sentence_separators);

        conf.locale = saveLocale;
        orig.updateConfiguration(conf, orig.getDisplayMetrics());
    }

    @Override
    public void onDestroy() {
        if (mUserDictionary != null) {
            mUserDictionary.close();
        }
        if (mContactsDictionary != null) {
            mContactsDictionary.close();
        }
        unregisterReceiver(mReceiver);
        unregisterReceiver(mPluginManager);
        if (VOICE_INSTALLED && mVoiceInput != null) {
            mVoiceInput.destroy();
        }
        LatinImeLogger.commit();
        LatinImeLogger.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration conf) {
        Log.i("PCKeyboard", "onConfigurationChanged()");
        // If the system locale changes and is different from the saved
        // locale (mSystemLocale), then reload the input locale list from the
        // latin ime settings (shared prefs) and reset the input locale
        // to the first one.
        final String systemLocale = conf.locale.toString();
        if (!TextUtils.equals(systemLocale, mSystemLocale)) {
            mSystemLocale = systemLocale;
            if (mLanguageSwitcher != null) {
                mLanguageSwitcher.loadLocales(PreferenceManager
                        .getDefaultSharedPreferences(this));
                mLanguageSwitcher.setSystemLocale(conf.locale);
                toggleLanguage(true, true);
            } else {
                reloadKeyboards();
            }
        }
        // If orientation changed while predicting, commit the change
        if (conf.orientation != mOrientation) {
            InputConnection ic = getCurrentInputConnection();
            commitTyped(ic);
            if (ic != null)
                ic.finishComposingText(); // For voice input
            mOrientation = conf.orientation;
            reloadKeyboards();
        }
        mConfigurationChanging = true;
        super.onConfigurationChanged(conf);
        if (mRecognizing) {
            switchToRecognitionStatusView();
        }
        mConfigurationChanging = false;
    }

    @Override
    public View onCreateInputView() {
        mKeyboardSwitcher.recreateInputView();
        mKeyboardSwitcher.makeKeyboards(true);
        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT, 0,
                shouldShowVoiceButton(makeFieldContext(),
                        getCurrentInputEditorInfo()));
        return mKeyboardSwitcher.getInputView();
    }

    @Override
    public View onCreateCandidatesView() {
        mKeyboardSwitcher.makeKeyboards(true);
        // Respect the suggestion settings in legacy Gingerbread mode,
        // in portrait mode, or if suggestions in landscape enabled.
        if (suggestionsDisabled()) {
            mCandidateViewContainer = null;
            mCandidateView = null;
            return mCandidateView;
        }
        mCandidateViewContainer = (LinearLayout) getLayoutInflater().inflate(
                R.layout.candidates, null);
        mCandidateView = (CandidateView) mCandidateViewContainer
                .findViewById(R.id.candidates);
        mCandidateView.setService(this);
        setCandidatesViewShown(true);
        return mCandidateViewContainer;
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        //Log.i("PCKeyboard", "onStartInputView " + attribute + " " + restarting);
        LatinKeyboardView inputView = mKeyboardSwitcher.getInputView();
        // In landscape mode, this method gets called without the input view
        // being created.
        if (inputView == null) {
            return;
        }

        if (mRefreshKeyboardRequired) {
            mRefreshKeyboardRequired = false;
            toggleLanguage(true, true);
        }

        mKeyboardSwitcher.makeKeyboards(false);

        TextEntryState.newSession(this);

        // Most such things we decide below in the switch statement, but we need
        // to know
        // now whether this is a password text field, because we need to know
        // now (before
        // the switch statement) whether we want to enable the voice button.
        mPasswordText = false;
        int variation = attribute.inputType & EditorInfo.TYPE_MASK_VARIATION;
        if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                || variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
            mPasswordText = true;
        }

        mEnableVoiceButton = shouldShowVoiceButton(makeFieldContext(),
                attribute);
        final boolean enableVoiceButton = mEnableVoiceButton && mEnableVoice;

        mAfterVoiceInput = false;
        mImmediatelyAfterVoiceInput = false;
        mShowingVoiceSuggestions = false;
        mVoiceInputHighlighted = false;
        mInputTypeNoAutoCorrect = false;
        mPredictionOn = false;
        mCompletionOn = false;
        mCompletions = null;
        mCapsLock = false;
        mModShift = false;
        mModCtrl = false;
        mModAlt = false;
        mModFn = false;
        mEnteredText = null;

        switch (attribute.inputType & EditorInfo.TYPE_MASK_CLASS) {
        case EditorInfo.TYPE_CLASS_NUMBER:
        case EditorInfo.TYPE_CLASS_DATETIME:
            // fall through
            // NOTE: For now, we use the phone keyboard for NUMBER and DATETIME
            // until we get
            // a dedicated number entry keypad.
            // TODO: Use a dedicated number entry keypad here when we get one.
        case EditorInfo.TYPE_CLASS_PHONE:
            mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_PHONE,
                    attribute.imeOptions, enableVoiceButton);
            break;
        case EditorInfo.TYPE_CLASS_TEXT:
            mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT,
                    attribute.imeOptions, enableVoiceButton);
            // startPrediction();
            mPredictionOn = true;
            // Make sure that passwords are not displayed in candidate view
            if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
                mPredictionOn = false;
            }
            if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    || variation == EditorInfo.TYPE_TEXT_VARIATION_PERSON_NAME) {
                mAutoSpace = false;
            } else {
                mAutoSpace = true;
            }
            if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) {
                mPredictionOn = false;
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_EMAIL,
                        attribute.imeOptions, enableVoiceButton);
            } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_URI) {
                mPredictionOn = false;
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_URL,
                        attribute.imeOptions, enableVoiceButton);
            } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE) {
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_IM,
                        attribute.imeOptions, enableVoiceButton);
            } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_FILTER) {
                mPredictionOn = false;
            } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT) {
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_WEB,
                        attribute.imeOptions, enableVoiceButton);
                // If it's a browser edit field and auto correct is not ON
                // explicitly, then
                // disable auto correction, but keep suggestions on.
                if ((attribute.inputType & EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT) == 0) {
                    mInputTypeNoAutoCorrect = true;
                }
            }

            // If NO_SUGGESTIONS is set, don't do prediction.
            if ((attribute.inputType & EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
                mPredictionOn = false;
                mInputTypeNoAutoCorrect = true;
            }
            // If it's not multiline and the autoCorrect flag is not set, then
            // don't correct
            if ((attribute.inputType & EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT) == 0
                    && (attribute.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) == 0) {
                mInputTypeNoAutoCorrect = true;
            }
            if ((attribute.inputType & EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) {
                mPredictionOn = false;
                mCompletionOn = isFullscreenMode();
            }
            break;
        default:
            mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT,
                    attribute.imeOptions, enableVoiceButton);
        }
        inputView.closing();
        mComposing.setLength(0);
        mPredicting = false;
        mDeleteCount = 0;
        mJustAddedAutoSpace = false;
        loadSettings();
        updateShiftKeyState(attribute);

        setCandidatesViewShownInternal(isCandidateStripVisible()
                || mCompletionOn, false /* needsInputViewShown */);
        updateSuggestions();

        // If the dictionary is not big enough, don't auto correct
        mHasDictionary = mSuggest.hasMainDictionary();

        updateCorrectionMode();

        inputView.setPreviewEnabled(mPopupOn);
        inputView.setProximityCorrectionEnabled(true);
        mPredictionOn = mPredictionOn
                && (mCorrectionMode > 0 || mShowSuggestions);
        if (suggestionsDisabled()) mPredictionOn = false;
        // If we just entered a text field, maybe it has some old text that
        // requires correction
        checkReCorrectionOnStart();
        checkTutorial(attribute.privateImeOptions);
        if (TRACE)
            Debug.startMethodTracing("/data/trace/latinime");
    }

    private void checkReCorrectionOnStart() {
        if (mReCorrectionEnabled && isPredictionOn()) {
            // First get the cursor position. This is required by
            // setOldSuggestions(), so that
            // it can pass the correct range to setComposingRegion(). At this
            // point, we don't
            // have valid values for mLastSelectionStart/Stop because
            // onUpdateSelection() has
            // not been called yet.
            InputConnection ic = getCurrentInputConnection();
            if (ic == null)
                return;
            ExtractedTextRequest etr = new ExtractedTextRequest();
            etr.token = 0; // anything is fine here
            ExtractedText et = ic.getExtractedText(etr, 0);
            if (et == null)
                return;

            mLastSelectionStart = et.startOffset + et.selectionStart;
            mLastSelectionEnd = et.startOffset + et.selectionEnd;

            // Then look for possible corrections in a delayed fashion
            if (!TextUtils.isEmpty(et.text) && isCursorTouchingWord()) {
                postUpdateOldSuggestions();
            }
        }
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();

        LatinImeLogger.commit();
        onAutoCompletionStateChanged(false);

        if (VOICE_INSTALLED && !mConfigurationChanging) {
            if (mAfterVoiceInput) {
                mVoiceInput.flushAllTextModificationCounters();
                mVoiceInput.logInputEnded();
            }
            mVoiceInput.flushLogs();
            mVoiceInput.cancel();
        }
        if (mKeyboardSwitcher.getInputView() != null) {
            mKeyboardSwitcher.getInputView().closing();
        }
        if (mAutoDictionary != null)
            mAutoDictionary.flushPendingWrites();
        if (mUserBigramDictionary != null)
            mUserBigramDictionary.flushPendingWrites();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        // Remove penging messages related to update suggestions
        mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS);
        mHandler.removeMessages(MSG_UPDATE_OLD_SUGGESTIONS);
    }

    @Override
    public void onUpdateExtractedText(int token, ExtractedText text) {
        super.onUpdateExtractedText(token, text);
        InputConnection ic = getCurrentInputConnection();
        if (!mImmediatelyAfterVoiceInput && mAfterVoiceInput && ic != null) {
            if (mHints.showPunctuationHintIfNecessary(ic)) {
                mVoiceInput.logPunctuationHintDisplayed();
            }
        }
        mImmediatelyAfterVoiceInput = false;
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd, int candidatesStart,
            int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);

        if (DEBUG) {
            Log.i(TAG, "onUpdateSelection: oss=" + oldSelStart + ", ose="
                    + oldSelEnd + ", nss=" + newSelStart + ", nse=" + newSelEnd
                    + ", cs=" + candidatesStart + ", ce=" + candidatesEnd);
        }

        if (mAfterVoiceInput) {
            mVoiceInput.setCursorPos(newSelEnd);
            mVoiceInput.setSelectionSpan(newSelEnd - newSelStart);
        }

        // If the current selection in the text view changes, we should
        // clear whatever candidate text we have.
        if ((((mComposing.length() > 0 && mPredicting) || mVoiceInputHighlighted)
                && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd) && mLastSelectionStart != newSelStart)) {
            mComposing.setLength(0);
            mPredicting = false;
            postUpdateSuggestions();
            TextEntryState.reset();
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.finishComposingText();
            }
            mVoiceInputHighlighted = false;
        } else if (!mPredicting && !mJustAccepted) {
            switch (TextEntryState.getState()) {
            case ACCEPTED_DEFAULT:
                TextEntryState.reset();
                // fall through
            case SPACE_AFTER_PICKED:
                mJustAddedAutoSpace = false; // The user moved the cursor.
                break;
            }
        }
        mJustAccepted = false;
        postUpdateShiftKeyState();

        // Make a note of the cursor position
        mLastSelectionStart = newSelStart;
        mLastSelectionEnd = newSelEnd;

        if (mReCorrectionEnabled) {
            // Don't look for corrections if the keyboard is not visible
            if (mKeyboardSwitcher != null
                    && mKeyboardSwitcher.getInputView() != null
                    && mKeyboardSwitcher.getInputView().isShown()) {
                // Check if we should go in or out of correction mode.
                if (isPredictionOn()
                        && mJustRevertedSeparator == null
                        && (candidatesStart == candidatesEnd
                                || newSelStart != oldSelStart || TextEntryState
                                .isCorrecting())
                        && (newSelStart < newSelEnd - 1 || (!mPredicting))
                        && !mVoiceInputHighlighted) {
                    if (isCursorTouchingWord()
                            || mLastSelectionStart < mLastSelectionEnd) {
                        postUpdateOldSuggestions();
                    } else {
                        abortCorrection(false);
                        // Show the punctuation suggestions list if the current
                        // one is not
                        // and if not showing "Touch again to save".
                        if (mCandidateView != null
                                && !mSuggestPuncList.equals(mCandidateView
                                        .getSuggestions())
                                && !mCandidateView
                                        .isShowingAddToDictionaryHint()) {
                            setNextSuggestions();
                        }
                    }
                }
            }
        }
    }

    /**
     * This is called when the user has clicked on the extracted text view, when
     * running in fullscreen mode. The default implementation hides the
     * candidates view when this happens, but only if the extracted text editor
     * has a vertical scroll bar because its text doesn't fit. Here we override
     * the behavior due to the possibility that a re-correction could cause the
     * candidate strip to disappear and re-appear.
     */
    @Override
    public void onExtractedTextClicked() {
        if (mReCorrectionEnabled && isPredictionOn())
            return;

        super.onExtractedTextClicked();
    }

    /**
     * This is called when the user has performed a cursor movement in the
     * extracted text view, when it is running in fullscreen mode. The default
     * implementation hides the candidates view when a vertical movement
     * happens, but only if the extracted text editor has a vertical scroll bar
     * because its text doesn't fit. Here we override the behavior due to the
     * possibility that a re-correction could cause the candidate strip to
     * disappear and re-appear.
     */
    @Override
    public void onExtractedCursorMovement(int dx, int dy) {
        if (mReCorrectionEnabled && isPredictionOn())
            return;

        super.onExtractedCursorMovement(dx, dy);
    }

    @Override
    public void hideWindow() {
        LatinImeLogger.commit();
        onAutoCompletionStateChanged(false);

        if (TRACE)
            Debug.stopMethodTracing();
        if (mOptionsDialog != null && mOptionsDialog.isShowing()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        if (!mConfigurationChanging) {
            if (mAfterVoiceInput)
                mVoiceInput.logInputEnded();
            if (mVoiceWarningDialog != null && mVoiceWarningDialog.isShowing()) {
                mVoiceInput.logKeyboardWarningDialogDismissed();
                mVoiceWarningDialog.dismiss();
                mVoiceWarningDialog = null;
            }
            if (VOICE_INSTALLED & mRecognizing) {
                mVoiceInput.cancel();
            }
        }
        mWordToSuggestions.clear();
        mWordHistory.clear();
        super.hideWindow();
        TextEntryState.endSession();
    }

    @Override
    public void onDisplayCompletions(CompletionInfo[] completions) {
        if (DEBUG) {
            Log.i("foo", "Received completions:");
            for (int i = 0; i < (completions != null ? completions.length : 0); i++) {
                Log.i("foo", "  #" + i + ": " + completions[i]);
            }
        }
        if (mCompletionOn) {
            mCompletions = completions;
            if (completions == null) {
                clearSuggestions();
                return;
            }

            List<CharSequence> stringList = new ArrayList<CharSequence>();
            for (int i = 0; i < (completions != null ? completions.length : 0); i++) {
                CompletionInfo ci = completions[i];
                if (ci != null)
                    stringList.add(ci.getText());
            }
            // When in fullscreen mode, show completions generated by the
            // application
            setSuggestions(stringList, true, true, true);
            mBestWord = null;
            setCandidatesViewShown(true);
        }
    }

    private void setCandidatesViewShownInternal(boolean shown,
            boolean needsInputViewShown) {
        // TODO: Remove this if we support candidates with hard keyboard
        if (onEvaluateInputViewShown()) {
            super.setCandidatesViewShown(shown
                    && mKeyboardSwitcher.getInputView() != null
                    && (needsInputViewShown ? mKeyboardSwitcher.getInputView()
                            .isShown() : true));
        }
    }

    @Override
    public void setCandidatesViewShown(boolean shown) {
        setCandidatesViewShownInternal(shown, true /* needsInputViewShown */);
    }

    @Override
    public void onComputeInsets(InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (!isFullscreenMode()) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets;
        }
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float displayHeight = dm.heightPixels;
        // If the display is more than X inches high, don't go to fullscreen
        // mode
        float dimen = getResources().getDimension(
                R.dimen.max_height_for_fullscreen);
        if (displayHeight > dimen || mFullscreenOverride || isConnectbot()) {
            return false;
        } else {
            return super.onEvaluateFullscreenMode();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_BACK:
            if (event.getRepeatCount() == 0
                    && mKeyboardSwitcher.getInputView() != null) {
                if (mKeyboardSwitcher.getInputView().handleBack()) {
                    return true;
                } else if (mTutorial != null) {
                    mTutorial.close();
                    mTutorial = null;
                }
            }
            break;
        case KeyEvent.KEYCODE_DPAD_DOWN:
        case KeyEvent.KEYCODE_DPAD_UP:
        case KeyEvent.KEYCODE_DPAD_LEFT:
        case KeyEvent.KEYCODE_DPAD_RIGHT:
            // If tutorial is visible, don't allow dpad to work
            if (mTutorial != null) {
                return true;
            }
            break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_DPAD_DOWN:
        case KeyEvent.KEYCODE_DPAD_UP:
        case KeyEvent.KEYCODE_DPAD_LEFT:
        case KeyEvent.KEYCODE_DPAD_RIGHT:
            // If tutorial is visible, don't allow dpad to work
            if (mTutorial != null) {
                return true;
            }
            LatinKeyboardView inputView = mKeyboardSwitcher.getInputView();
            // Enable shift key and DPAD to do selections
            if (inputView != null && inputView.isShown()
                    && inputView.isShifted()) {
                event = new KeyEvent(event.getDownTime(), event.getEventTime(),
                        event.getAction(), event.getKeyCode(), event
                                .getRepeatCount(), event.getDeviceId(), event
                                .getScanCode(), KeyEvent.META_SHIFT_LEFT_ON
                                | KeyEvent.META_SHIFT_ON);
                InputConnection ic = getCurrentInputConnection();
                if (ic != null)
                    ic.sendKeyEvent(event);
                return true;
            }
            break;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void revertVoiceInput() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null)
            ic.commitText("", 1);
        updateSuggestions();
        mVoiceInputHighlighted = false;
    }

    private void commitVoiceInput() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null)
            ic.finishComposingText();
        updateSuggestions();
        mVoiceInputHighlighted = false;
    }

    private void reloadKeyboards() {
        mKeyboardSwitcher.setLanguageSwitcher(mLanguageSwitcher);
        if (mKeyboardSwitcher.getInputView() != null
                && mKeyboardSwitcher.getKeyboardMode() != KeyboardSwitcher.MODE_NONE) {
            mKeyboardSwitcher.setVoiceMode(mEnableVoice && mEnableVoiceButton,
                    mVoiceOnPrimary);
        }
        mKeyboardSwitcher.setFullKeyboardOptions(mFullInPortrait,
                mHeightPortrait, mHeightLandscape, mHintMode);
        mKeyboardSwitcher.makeKeyboards(true);
    }

    private void commitTyped(InputConnection inputConnection) {
        if (mPredicting) {
            mPredicting = false;
            if (mComposing.length() > 0) {
                if (inputConnection != null) {
                    inputConnection.commitText(mComposing, 1);
                }
                mCommittedLength = mComposing.length();
                TextEntryState.acceptedTyped(mComposing);
                addToDictionaries(mComposing,
                        AutoDictionary.FREQUENCY_FOR_TYPED);
            }
            updateSuggestions();
        }
    }

    private void postUpdateShiftKeyState() {
        mHandler.removeMessages(MSG_UPDATE_SHIFT_STATE);
        // TODO: Should remove this 300ms delay?
        mHandler.sendMessageDelayed(mHandler
                .obtainMessage(MSG_UPDATE_SHIFT_STATE), 300);
    }

    public void updateShiftKeyState(EditorInfo attr) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && attr != null && mKeyboardSwitcher.isAlphabetMode()) {
        	//Log.i(TAG, "updateShiftKeyState capsLock=" +mCapsLock + " isMomentary=" + mShiftKeyState.isMomentary() + " cursorCaps=" + getCursorCapsMode(ic, attr));
            mKeyboardSwitcher.setShifted(mShiftKeyState.isMomentary()
                    || mCapsLock || getCursorCapsMode(ic, attr) != 0);
        }
    }

    private int getCursorCapsMode(InputConnection ic, EditorInfo attr) {
        int caps = 0;
        EditorInfo ei = getCurrentInputEditorInfo();
        if (mAutoCap && ei != null && ei.inputType != EditorInfo.TYPE_NULL) {
            caps = ic.getCursorCapsMode(attr.inputType);
        }
        return caps;
    }

    private void swapPunctuationAndSpace() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        CharSequence lastTwo = ic.getTextBeforeCursor(2, 0);
        if (lastTwo != null && lastTwo.length() == 2
                && lastTwo.charAt(0) == KEYCODE_SPACE
                && isSentenceSeparator(lastTwo.charAt(1))) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(2, 0);
            ic.commitText(lastTwo.charAt(1) + " ", 1);
            ic.endBatchEdit();
            updateShiftKeyState(getCurrentInputEditorInfo());
            mJustAddedAutoSpace = true;
        }
    }

    private void reswapPeriodAndSpace() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        CharSequence lastThree = ic.getTextBeforeCursor(3, 0);
        if (lastThree != null && lastThree.length() == 3
                && lastThree.charAt(0) == KEYCODE_PERIOD
                && lastThree.charAt(1) == KEYCODE_SPACE
                && lastThree.charAt(2) == KEYCODE_PERIOD) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(3, 0);
            ic.commitText(" ..", 1);
            ic.endBatchEdit();
            updateShiftKeyState(getCurrentInputEditorInfo());
        }
    }

    private void doubleSpace() {
        // if (!mAutoPunctuate) return;
        if (mCorrectionMode == Suggest.CORRECTION_NONE)
            return;
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        CharSequence lastThree = ic.getTextBeforeCursor(3, 0);
        if (lastThree != null && lastThree.length() == 3
                && Character.isLetterOrDigit(lastThree.charAt(0))
                && lastThree.charAt(1) == KEYCODE_SPACE
                && lastThree.charAt(2) == KEYCODE_SPACE) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(2, 0);
            ic.commitText(". ", 1);
            ic.endBatchEdit();
            updateShiftKeyState(getCurrentInputEditorInfo());
            mJustAddedAutoSpace = true;
        }
    }

    private void maybeRemovePreviousPeriod(CharSequence text) {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;

        // When the text's first character is '.', remove the previous period
        // if there is one.
        CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
        if (lastOne != null && lastOne.length() == 1
                && lastOne.charAt(0) == KEYCODE_PERIOD
                && text.charAt(0) == KEYCODE_PERIOD) {
            ic.deleteSurroundingText(1, 0);
        }
    }

    private void removeTrailingSpace() {
        final InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;

        CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
        if (lastOne != null && lastOne.length() == 1
                && lastOne.charAt(0) == KEYCODE_SPACE) {
            ic.deleteSurroundingText(1, 0);
        }
    }

    public boolean addWordToDictionary(String word) {
        mUserDictionary.addWord(word, 128);
        // Suggestion strip should be updated after the operation of adding word
        // to the
        // user dictionary
        postUpdateSuggestions();
        return true;
    }

    private boolean isAlphabet(int code) {
        if (Character.isLetter(code)) {
            return true;
        } else {
            return false;
        }
    }

    private void showInputMethodPicker() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showInputMethodPicker();
    }

    private void onOptionKeyPressed() {
        if (!isShowingOptionDialog()) {
            if (LatinIMEUtil.hasMultipleEnabledIMEs(this)) {
                showOptionsMenu();
            } else {
                launchSettings();
            }
        }
    }

    private void onOptionKeyLongPressed() {
        if (!isShowingOptionDialog()) {
            if (LatinIMEUtil.hasMultipleEnabledIMEs(this)) {
                showInputMethodPicker();
            } else {
                launchSettings();
            }
        }
    }

    private boolean isShowingOptionDialog() {
        return mOptionsDialog != null && mOptionsDialog.isShowing();
    }

    private boolean isConnectbot() {
        EditorInfo ei = getCurrentInputEditorInfo();
        String pkg = ei.packageName;
        return (pkg.equalsIgnoreCase("org.connectbot") && ei.inputType == 0); // FIXME
    }

    
    private void sendKeyDown(InputConnection ic, int key) {
    	if (ic != null) ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
    }

    private void sendKeyUp(InputConnection ic, int key) {
    	if (ic != null) ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    private void sendModifierKeysDown(boolean mayShift) {
        InputConnection ic = getCurrentInputConnection();
        if (mayShift && mModShift) {
            sendKeyDown(ic, KeyEvent.KEYCODE_SHIFT_LEFT);
        }
        if (mModCtrl && !mCtrlKeyState.isMomentary()) {
            sendKeyDown(ic, 113); // KeyEvent.KEYCODE_CTRL_LEFT
        }
        if (mModAlt && !mAltKeyState.isMomentary()) {
            sendKeyDown(ic, 57); // KeyEvent.KEYCODE_ALT_LEFT
        }
    }

    private void sendModifierKeysUp(boolean mayShift) {
        InputConnection ic = getCurrentInputConnection();
        if (mModAlt && !mAltKeyState.isMomentary()) {
            sendKeyUp(ic, 57); // KeyEvent.KEYCODE_ALT_LEFT
            setModShift(false);
        }
        if (mModCtrl && !mCtrlKeyState.isMomentary()) {
            sendKeyUp(ic, 113); // KeyEvent.KEYCODE_CTRL_LEFT
            setModCtrl(false);
        }
        if (mayShift && mModShift) {
            sendKeyUp(ic, KeyEvent.KEYCODE_SHIFT_LEFT);
            if (!mShiftKeyState.isMomentary()) setModAlt(false);
        }
    }

    private void sendSpecialKey(int code) {
        if (!isConnectbot()) {
            sendDownUpKeyEvents(-code);
            return;
        }

        if (ESC_SEQUENCES == null) {
            ESC_SEQUENCES = new HashMap<Integer, String>();

            // VT escape sequences without leading Escape
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_HOME, "[1~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_END, "[4~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_PAGE_UP, "[5~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_PAGE_DOWN, "[6~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F1, "OP");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F2, "OQ");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F3, "OR");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F4, "OS");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F5, "[15~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F6, "[17~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F7, "[18~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F8, "[19~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F9, "[20~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F10, "[21~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F11, "[23~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FKEY_F12, "[24~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_FORWARD_DEL, "[3~");
            ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_INSERT, "[2~");

            // Natively supported by ConnectBot
            // ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_DPAD_UP, "OA");
            // ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_DPAD_DOWN, "OB");
            // ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_DPAD_LEFT, "OD");
            // ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_DPAD_RIGHT, "OC");

            // No VT equivalents?
            // ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_DPAD_CENTER, "");
            // ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_SYSRQ, "");
            // ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_BREAK, "");
            // ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_NUM_LOCK, "");
            // ESC_SEQUENCES.put(LatinKeyboardView.KEYCODE_SCROLL_LOCK, "");
        }
        InputConnection ic = getCurrentInputConnection();
        String seq = ESC_SEQUENCES.get(code);
        if (seq != null) {
            if (mModAlt) {
                // send ESC prefix for "Meta"
                ic.commitText(Character.toString((char) 27), 1);
            }
            // send ESC prefix of escape sequence
            ic.commitText(Character.toString((char) 27), 1);
            ic.commitText(seq, 1);
        } else {
            // send key code, let connectbot handle it
            sendDownUpKeyEvents(-code);
        }
    }

    private void sendCharWithModifiers(int primaryCode, int[] keyCodes) {
        InputConnection ic = getCurrentInputConnection();
        if (isConnectbot()) {
            if (mModAlt) {
                // send ESC prefix
                ic.commitText(Character.toString((char) 27), 1);
            }
            if (mModCtrl && primaryCode >= 32 && primaryCode < 127) {
                int code = primaryCode & 31;
                if (code == 9) {
                    sendTab();
                } else {
                    ic.commitText(Character.toString((char) code), 1);
                }
            } else {
                handleCharacter(primaryCode, keyCodes);
            }
        } else {
            sendKeyChar((char) primaryCode);
            // handleCharacter(primaryCode, keyCodes);
        }
    }

    private void sendTab() {
        InputConnection ic = getCurrentInputConnection();
        boolean tabHack = isConnectbot() && mConnectbotTabHack;

        // FIXME: tab and ^I don't work in connectbot, hackish workaround
        if (tabHack) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_CENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_DPAD_CENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_I));
            ic
                    .sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_I));
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_TAB));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_TAB));
        }
    }

    private void sendEscape() {
        InputConnection ic = getCurrentInputConnection();
        if (isConnectbot()) {
            sendKeyChar((char) 27);
        } else {
            ic
                    .sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, 111 /* KEYCODE_ESCAPE */));
            ic
                    .sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, 111 /* KEYCODE_ESCAPE */));
        }
    }

    private static final HashMap<String, String> composeSequences;
    private boolean compose = false;
    private String composeBuffer = "";

    static {
        HashMap<String, String> cs = new HashMap<String, String>();
        // Compose sequences adapted from /usr/share/X11/locale/en_US.UTF-8/Compose
        cs.put("++", "#");
        cs.put("' ", "'");
        cs.put(" '", "'");
        cs.put("AT", "@");
        cs.put("((", "[");
        cs.put("//", "\\");
        cs.put("/<", "\\");
        cs.put("</", "\\");
        cs.put("))", "]");
        cs.put("^ ", "^");
        cs.put(" ^", "^");
        cs.put("> ", "^");
        cs.put(" >", "^");
        cs.put("` ", "`");
        cs.put(" `", "`");
        cs.put(", ", "¸");
        cs.put(" ,", "¸");
        cs.put("(-", "{");
        cs.put("-(", "{");
        cs.put("/^", "|");
        cs.put("^/", "|");
        cs.put("VL", "|");
        cs.put("LV", "|");
        cs.put("vl", "|");
        cs.put("lv", "|");
        cs.put(")-", "}");
        cs.put("-)", "}");
        cs.put("~ ", "~");
        cs.put(" ~", "~");
        cs.put("- ", "~");
        cs.put(" -", "~");
        cs.put("  ", " ");
        cs.put(" .", " ");
        cs.put("oc", "©");
        cs.put("oC", "©");
        cs.put("Oc", "©");
        cs.put("OC", "©");
        cs.put("or", "®");
        cs.put("oR", "®");
        cs.put("Or", "®");
        cs.put("OR", "®");
        cs.put(".>", "›");
        cs.put(".<", "‹");
        cs.put("..", "…");
        cs.put(".-", "·");
        cs.put(".=", "•");
        cs.put("!^", "¦");
        cs.put("!!", "¡");
        cs.put("p!", "¶");
        cs.put("P!", "¶");
        cs.put("+-", "±");
        cs.put("??", "¿");
        cs.put("-d", "đ");
        cs.put("-D", "Đ");
        cs.put("ss", "ß");
        cs.put("SS", "ẞ");
        cs.put("oe", "œ");
        cs.put("OE", "Œ");
        cs.put("ae", "æ");
        cs.put("AE", "Æ");
        cs.put("oo", "°");
        cs.put("\"\\", "〝");
        cs.put("\"/", "〞");
        cs.put("<<", "«");
        cs.put(">>", "»");
        cs.put("<'", "‘");
        cs.put("'<", "‘");
        cs.put(">'", "’");
        cs.put("'>", "’");
        cs.put(",'", "‚");
        cs.put("',", "‚");
        cs.put("<\"", "“");
        cs.put("\"<", "“");
        cs.put(">\"", "”");
        cs.put("\">", "”");
        cs.put(",\"", "„");
        cs.put("\",", "„");
        cs.put("%o", "‰");
        cs.put("CE", "₠");
        cs.put("C/", "₡");
        cs.put("/C", "₡");
        cs.put("Cr", "₢");
        cs.put("Fr", "₣");
        cs.put("L=", "₤");
        cs.put("=L", "₤");
        cs.put("m/", "₥");
        cs.put("/m", "₥");
        cs.put("N=", "₦");
        cs.put("=N", "₦");
        cs.put("Pt", "₧");
        cs.put("Rs", "₨");
        cs.put("W=", "₩");
        cs.put("=W", "₩");
        cs.put("d-", "₫");
        cs.put("C=", "€");
        cs.put("=C", "€");
        cs.put("c=", "€");
        cs.put("=c", "€");
        cs.put("E=", "€");
        cs.put("=E", "€");
        cs.put("e=", "€");
        cs.put("=e", "€");
        cs.put("|c", "¢");
        cs.put("c|", "¢");
        cs.put("c/", "¢");
        cs.put("/c", "¢");
        cs.put("L-", "£");
        cs.put("-L", "£");
        cs.put("Y=", "¥");
        cs.put("=Y", "¥");
        cs.put("fs", "ſ");
        cs.put("fS", "ſ");
        cs.put("--.", "–");
        cs.put("---", "—");
        cs.put("#b", "♭");
        cs.put("#f", "♮");
        cs.put("##", "♯");
        cs.put("so", "§");
        cs.put("os", "§");
        cs.put("ox", "¤");
        cs.put("xo", "¤");
        cs.put("PP", "¶");
        cs.put("No", "№");
        cs.put("NO", "№");
        cs.put("?!", "⸘");
        cs.put("!?", "‽");
        cs.put("CCCP", "☭");
        cs.put("OA", "Ⓐ");
        cs.put("<3", "♥");
        cs.put(":)", "☺");
        cs.put(":(", "☹");
        cs.put(",-", "¬");
        cs.put("-,", "¬");
        cs.put("^_a", "ª");
        cs.put("^2", "²");
        cs.put("^3", "³");
        cs.put("mu", "µ");
        cs.put("^1", "¹");
        cs.put("^_o", "º");
        cs.put("14", "¼");
        cs.put("12", "½");
        cs.put("34", "¾");
        cs.put("`A", "À");
        cs.put("'A", "Á");
        cs.put("^A", "Â");
        cs.put("~A", "Ã");
        cs.put("\"A", "Ä");
        cs.put("oA", "Å");
        cs.put(",C", "Ç");
        cs.put("`E", "È");
        cs.put("'E", "É");
        cs.put("^E", "Ê");
        cs.put("\"E", "Ë");
        cs.put("`I", "Ì");
        cs.put("'I", "Í");
        cs.put("^I", "Î");
        cs.put("\"I", "Ï");
        cs.put("DH", "Ð");
        cs.put("~N", "Ñ");
        cs.put("`O", "Ò");
        cs.put("'O", "Ó");
        cs.put("^O", "Ô");
        cs.put("~O", "Õ");
        cs.put("\"O", "Ö");
        cs.put("xx", "×");
        cs.put("/O", "Ø");
        cs.put("`U", "Ù");
        cs.put("'U", "Ú");
        cs.put("^U", "Û");
        cs.put("\"U", "Ü");
        cs.put("'Y", "Ý");
        cs.put("TH", "Þ");
        cs.put("`a", "à");
        cs.put("'a", "á");
        cs.put("^a", "â");
        cs.put("~a", "ã");
        cs.put("\"a", "ä");
        cs.put("oa", "å");
        cs.put(",c", "ç");
        cs.put("`e", "è");
        cs.put("'e", "é");
        cs.put("^e", "ê");
        cs.put("\"e", "ë");
        cs.put("`i", "ì");
        cs.put("'i", "í");
        cs.put("^i", "î");
        cs.put("\"i", "ï");
        cs.put("dh", "ð");
        cs.put("~n", "ñ");
        cs.put("`o", "ò");
        cs.put("'o", "ó");
        cs.put("^o", "ô");
        cs.put("~o", "õ");
        cs.put("\"o", "ö");
        cs.put(":-", "÷");
        cs.put("-:", "÷");
        cs.put("/o", "ø");
        cs.put("`u", "ù");
        cs.put("'u", "ú");
        cs.put("^u", "û");
        cs.put("\"u", "ü");
        cs.put("'y", "ý");
        cs.put("th", "þ");
        cs.put("\"y", "ÿ");
        cs.put("_A", "Ā");
        cs.put("_a", "ā");
        cs.put("UA", "Ă");
        cs.put("bA", "Ă");
        cs.put("Ua", "ă");
        cs.put("ba", "ă");
        cs.put(";A", "Ą");
        cs.put(",A", "Ą");
        cs.put(";a", "ą");
        cs.put(",a", "ą");
        cs.put("'C", "Ć");
        cs.put("'c", "ć");
        cs.put("^C", "Ĉ");
        cs.put("^c", "ĉ");
        cs.put(".C", "Ċ");
        cs.put(".c", "ċ");
        cs.put("cC", "Č");
        cs.put("cc", "č");
        cs.put("cD", "Ď");
        cs.put("cd", "ď");
        cs.put("/D", "Đ");
        cs.put("/d", "đ");
        cs.put("_E", "Ē");
        cs.put("_e", "ē");
        cs.put("UE", "Ĕ");
        cs.put("bE", "Ĕ");
        cs.put("Ue", "ĕ");
        cs.put("be", "ĕ");
        cs.put(".E", "Ė");
        cs.put(".e", "ė");
        cs.put(";E", "Ę");
        cs.put(",E", "Ę");
        cs.put(";e", "ę");
        cs.put(",e", "ę");
        cs.put("cE", "Ě");
        cs.put("ce", "ě");
        cs.put("^G", "Ĝ");
        cs.put("^g", "ĝ");
        cs.put("UG", "Ğ");
        cs.put("bG", "Ğ");
        cs.put("Ug", "ğ");
        cs.put("bg", "ğ");
        cs.put(".G", "Ġ");
        cs.put(".g", "ġ");
        cs.put(",G", "Ģ");
        cs.put(",g", "ģ");
        cs.put("^H", "Ĥ");
        cs.put("^h", "ĥ");
        cs.put("/H", "Ħ");
        cs.put("/h", "ħ");
        cs.put("~I", "Ĩ");
        cs.put("~i", "ĩ");
        cs.put("_I", "Ī");
        cs.put("_i", "ī");
        cs.put("UI", "Ĭ");
        cs.put("bI", "Ĭ");
        cs.put("Ui", "ĭ");
        cs.put("bi", "ĭ");
        cs.put(";I", "Į");
        cs.put(",I", "Į");
        cs.put(";i", "į");
        cs.put(",i", "į");
        cs.put(".I", "İ");
        cs.put("i.", "ı");
        cs.put("^J", "Ĵ");
        cs.put("^j", "ĵ");
        cs.put(",K", "Ķ");
        cs.put(",k", "ķ");
        cs.put("kk", "ĸ");
        cs.put("'L", "Ĺ");
        cs.put("'l", "ĺ");
        cs.put(",L", "Ļ");
        cs.put(",l", "ļ");
        cs.put("cL", "Ľ");
        cs.put("cl", "ľ");
        cs.put("/L", "Ł");
        cs.put("/l", "ł");
        cs.put("'N", "Ń");
        cs.put("'n", "ń");
        cs.put(",N", "Ņ");
        cs.put(",n", "ņ");
        cs.put("cN", "Ň");
        cs.put("cn", "ň");
        cs.put("NG", "Ŋ");
        cs.put("ng", "ŋ");
        cs.put("_O", "Ō");
        cs.put("_o", "ō");
        cs.put("UO", "Ŏ");
        cs.put("bO", "Ŏ");
        cs.put("Uo", "ŏ");
        cs.put("bo", "ŏ");
        cs.put("=O", "Ő");
        cs.put("=o", "ő");
        cs.put("'R", "Ŕ");
        cs.put("'r", "ŕ");
        cs.put(",R", "Ŗ");
        cs.put(",r", "ŗ");
        cs.put("cR", "Ř");
        cs.put("cr", "ř");
        cs.put("'S", "Ś");
        cs.put("'s", "ś");
        cs.put("^S", "Ŝ");
        cs.put("^s", "ŝ");
        cs.put(",S", "Ş");
        cs.put(",s", "ş");
        cs.put("cS", "Š");
        cs.put("cs", "š");
        cs.put(",T", "Ţ");
        cs.put(",t", "ţ");
        cs.put("cT", "Ť");
        cs.put("ct", "ť");
        cs.put("/T", "Ŧ");
        cs.put("/t", "ŧ");
        cs.put("~U", "Ũ");
        cs.put("~u", "ũ");
        cs.put("_U", "Ū");
        cs.put("_u", "ū");
        cs.put("UU", "Ŭ");
        cs.put("bU", "Ŭ");
        cs.put("Uu", "ŭ");
        cs.put("uu", "ŭ");
        cs.put("bu", "ŭ");
        cs.put("oU", "Ů");
        cs.put("ou", "ů");
        cs.put("=U", "Ű");
        cs.put("=u", "ű");
        cs.put(";U", "Ų");
        cs.put(",U", "Ų");
        cs.put(";u", "ų");
        cs.put(",u", "ų");
        cs.put("^W", "Ŵ");
        cs.put("^w", "ŵ");
        cs.put("^Y", "Ŷ");
        cs.put("^y", "ŷ");
        cs.put("\"Y", "Ÿ");
        cs.put("'Z", "Ź");
        cs.put("'z", "ź");
        cs.put(".Z", "Ż");
        cs.put(".z", "ż");
        cs.put("cZ", "Ž");
        cs.put("cz", "ž");
        cs.put("/b", "ƀ");
        cs.put("/I", "Ɨ");
        cs.put("+O", "Ơ");
        cs.put("+o", "ơ");
        cs.put("+U", "Ư");
        cs.put("+u", "ư");
        cs.put("/Z", "Ƶ");
        cs.put("/z", "ƶ");
        cs.put("cA", "Ǎ");
        cs.put("ca", "ǎ");
        cs.put("cI", "Ǐ");
        cs.put("ci", "ǐ");
        cs.put("cO", "Ǒ");
        cs.put("co", "ǒ");
        cs.put("cU", "Ǔ");
        cs.put("cu", "ǔ");
        cs.put("_Ü", "Ǖ");
        cs.put("_\"U", "Ǖ");
        cs.put("_ü", "ǖ");
        cs.put("_\"u", "ǖ");
        cs.put("'Ü", "Ǘ");
        cs.put("'\"U", "Ǘ");
        cs.put("'ü", "ǘ");
        cs.put("'\"u", "ǘ");
        cs.put("cÜ", "Ǚ");
        cs.put("c\"U", "Ǚ");
        cs.put("cü", "ǚ");
        cs.put("c\"u", "ǚ");
        cs.put("`Ü", "Ǜ");
        cs.put("`\"U", "Ǜ");
        cs.put("`ü", "ǜ");
        cs.put("`\"u", "ǜ");
        cs.put("_Ä", "Ǟ");
        cs.put("_\"A", "Ǟ");
        cs.put("_ä", "ǟ");
        cs.put("_\"a", "ǟ");
        cs.put("_.A", "Ǡ");
        cs.put("_.a", "ǡ");
        cs.put("_Æ", "Ǣ");
        cs.put("_æ", "ǣ");
        cs.put("/G", "Ǥ");
        cs.put("/g", "ǥ");
        cs.put("cG", "Ǧ");
        cs.put("cg", "ǧ");
        cs.put("cK", "Ǩ");
        cs.put("ck", "ǩ");
        cs.put(";O", "Ǫ");
        cs.put(";o", "ǫ");
        cs.put("_;O", "Ǭ");
        cs.put("_;o", "ǭ");
        cs.put("cj", "ǰ");
        cs.put("'G", "Ǵ");
        cs.put("'g", "ǵ");
        cs.put("`N", "Ǹ");
        cs.put("`n", "ǹ");
        cs.put("'Å", "Ǻ");
        cs.put("o'A", "Ǻ");
        cs.put("'å", "ǻ");
        cs.put("o'a", "ǻ");
        cs.put("'Æ", "Ǽ");
        cs.put("'æ", "ǽ");
        cs.put("'Ø", "Ǿ");
        cs.put("'/O", "Ǿ");
        cs.put("'ø", "ǿ");
        cs.put("'/o", "ǿ");
        cs.put("cH", "Ȟ");
        cs.put("ch", "ȟ");
        cs.put(".A", "Ȧ");
        cs.put(".a", "ȧ");
        cs.put("_Ö", "Ȫ");
        cs.put("_\"O", "Ȫ");
        cs.put("_ö", "ȫ");
        cs.put("_\"o", "ȫ");
        cs.put("_Õ", "Ȭ");
        cs.put("_~O", "Ȭ");
        cs.put("_õ", "ȭ");
        cs.put("_~o", "ȭ");
        cs.put(".O", "Ȯ");
        cs.put(".o", "ȯ");
        cs.put("_.O", "Ȱ");
        cs.put("_.o", "ȱ");
        cs.put("_Y", "Ȳ");
        cs.put("_y", "ȳ");
        cs.put("ee", "ə");
        cs.put("/i", "ɨ");
        cs.put("^_h", "ʰ");
        cs.put("^_j", "ʲ");
        cs.put("^_r", "ʳ");
        cs.put("^_w", "ʷ");
        cs.put("^_y", "ʸ");
        cs.put("^_l", "ˡ");
        cs.put("^_s", "ˢ");
        cs.put("^_x", "ˣ");
        cs.put("\"'", "̈́");
        cs.put(".B", "Ḃ");
        cs.put(".b", "ḃ");
        cs.put("!B", "Ḅ");
        cs.put("!b", "ḅ");
        cs.put("'Ç", "Ḉ");
        cs.put("'ç", "ḉ");
        cs.put(".D", "Ḋ");
        cs.put(".d", "ḋ");
        cs.put("!D", "Ḍ");
        cs.put("!d", "ḍ");
        cs.put(",D", "Ḑ");
        cs.put(",d", "ḑ");
        cs.put("`Ē", "Ḕ");
        cs.put("`_E", "Ḕ");
        cs.put("`ē", "ḕ");
        cs.put("`_e", "ḕ");
        cs.put("'Ē", "Ḗ");
        cs.put("'_E", "Ḗ");
        cs.put("'ē", "ḗ");
        cs.put("'_e", "ḗ");
        cs.put("U,E", "Ḝ");
        cs.put("b,E", "Ḝ");
        cs.put("U,e", "ḝ");
        cs.put("b,e", "ḝ");
        cs.put(".F", "Ḟ");
        cs.put(".f", "ḟ");
        cs.put("_G", "Ḡ");
        cs.put("_g", "ḡ");
        cs.put(".H", "Ḣ");
        cs.put(".h", "ḣ");
        cs.put("!H", "Ḥ");
        cs.put("!h", "ḥ");
        cs.put("\"H", "Ḧ");
        cs.put("\"h", "ḧ");
        cs.put(",H", "Ḩ");
        cs.put(",h", "ḩ");
        cs.put("'Ï", "Ḯ");
        cs.put("'\"I", "Ḯ");
        cs.put("'ï", "ḯ");
        cs.put("'\"i", "ḯ");
        cs.put("'K", "Ḱ");
        cs.put("'k", "ḱ");
        cs.put("!K", "Ḳ");
        cs.put("!k", "ḳ");
        cs.put("!L", "Ḷ");
        cs.put("!l", "ḷ");
        cs.put("_!L", "Ḹ");
        cs.put("_!l", "ḹ");
        cs.put("'M", "Ḿ");
        cs.put("'m", "ḿ");
        cs.put(".M", "Ṁ");
        cs.put(".m", "ṁ");
        cs.put("!M", "Ṃ");
        cs.put("!m", "ṃ");
        cs.put(".N", "Ṅ");
        cs.put(".n", "ṅ");
        cs.put("!N", "Ṇ");
        cs.put("!n", "ṇ");
        cs.put("'Õ", "Ṍ");
        cs.put("'~O", "Ṍ");
        cs.put("'õ", "ṍ");
        cs.put("'~o", "ṍ");
        cs.put("\"Õ", "Ṏ");
        cs.put("\"~O", "Ṏ");
        cs.put("\"õ", "ṏ");
        cs.put("\"~o", "ṏ");
        cs.put("`Ō", "Ṑ");
        cs.put("`_O", "Ṑ");
        cs.put("`ō", "ṑ");
        cs.put("`_o", "ṑ");
        cs.put("'Ō", "Ṓ");
        cs.put("'_O", "Ṓ");
        cs.put("'ō", "ṓ");
        cs.put("'_o", "ṓ");
        cs.put("'P", "Ṕ");
        cs.put("'p", "ṕ");
        cs.put(".P", "Ṗ");
        cs.put(".p", "ṗ");
        cs.put(".R", "Ṙ");
        cs.put(".r", "ṙ");
        cs.put("!R", "Ṛ");
        cs.put("!r", "ṛ");
        cs.put("_!R", "Ṝ");
        cs.put("_!r", "ṝ");
        cs.put(".S", "Ṡ");
        cs.put(".s", "ṡ");
        cs.put("!S", "Ṣ");
        cs.put("!s", "ṣ");
        cs.put(".Ś", "Ṥ");
        cs.put(".'S", "Ṥ");
        cs.put(".ś", "ṥ");
        cs.put(".'s", "ṥ");
        cs.put(".Š", "Ṧ");
        cs.put(".š", "ṧ");
        cs.put(".!S", "Ṩ");
        cs.put(".!s", "ṩ");
        cs.put(".T", "Ṫ");
        cs.put(".t", "ṫ");
        cs.put("!T", "Ṭ");
        cs.put("!t", "ṭ");
        cs.put("'Ũ", "Ṹ");
        cs.put("'~U", "Ṹ");
        cs.put("'ũ", "ṹ");
        cs.put("'~u", "ṹ");
        cs.put("\"Ū", "Ṻ");
        cs.put("\"_U", "Ṻ");
        cs.put("\"ū", "ṻ");
        cs.put("\"_u", "ṻ");
        cs.put("~V", "Ṽ");
        cs.put("~v", "ṽ");
        cs.put("!V", "Ṿ");
        cs.put("!v", "ṿ");
        cs.put("`W", "Ẁ");
        cs.put("`w", "ẁ");
        cs.put("'W", "Ẃ");
        cs.put("'w", "ẃ");
        cs.put("\"W", "Ẅ");
        cs.put("\"w", "ẅ");
        cs.put(".W", "Ẇ");
        cs.put(".w", "ẇ");
        cs.put("!W", "Ẉ");
        cs.put("!w", "ẉ");
        cs.put(".X", "Ẋ");
        cs.put(".x", "ẋ");
        cs.put("\"X", "Ẍ");
        cs.put("\"x", "ẍ");
        cs.put(".Y", "Ẏ");
        cs.put(".y", "ẏ");
        cs.put("^Z", "Ẑ");
        cs.put("^z", "ẑ");
        cs.put("!Z", "Ẓ");
        cs.put("!z", "ẓ");
        cs.put("\"t", "ẗ");
        cs.put("ow", "ẘ");
        cs.put("oy", "ẙ");
        cs.put("!A", "Ạ");
        cs.put("!a", "ạ");
        cs.put("?A", "Ả");
        cs.put("?a", "ả");
        cs.put("'Â", "Ấ");
        cs.put("'^A", "Ấ");
        cs.put("'â", "ấ");
        cs.put("'^a", "ấ");
        cs.put("`Â", "Ầ");
        cs.put("`^A", "Ầ");
        cs.put("`â", "ầ");
        cs.put("`^a", "ầ");
        cs.put("?Â", "Ẩ");
        cs.put("?^A", "Ẩ");
        cs.put("?â", "ẩ");
        cs.put("?^a", "ẩ");
        cs.put("~Â", "Ẫ");
        cs.put("~^A", "Ẫ");
        cs.put("~â", "ẫ");
        cs.put("~^a", "ẫ");
        cs.put("^!A", "Ậ");
        cs.put("^!a", "ậ");
        cs.put("'Ă", "Ắ");
        cs.put("'bA", "Ắ");
        cs.put("'ă", "ắ");
        cs.put("'ba", "ắ");
        cs.put("`Ă", "Ằ");
        cs.put("`bA", "Ằ");
        cs.put("`ă", "ằ");
        cs.put("`ba", "ằ");
        cs.put("?Ă", "Ẳ");
        cs.put("?bA", "Ẳ");
        cs.put("?ă", "ẳ");
        cs.put("?ba", "ẳ");
        cs.put("~Ă", "Ẵ");
        cs.put("~bA", "Ẵ");
        cs.put("~ă", "ẵ");
        cs.put("~ba", "ẵ");
        cs.put("U!A", "Ặ");
        cs.put("b!A", "Ặ");
        cs.put("U!a", "ặ");
        cs.put("b!a", "ặ");
        cs.put("!E", "Ẹ");
        cs.put("!e", "ẹ");
        cs.put("?E", "Ẻ");
        cs.put("?e", "ẻ");
        cs.put("~E", "Ẽ");
        cs.put("~e", "ẽ");
        cs.put("'Ê", "Ế");
        cs.put("'^E", "Ế");
        cs.put("'ê", "ế");
        cs.put("'^e", "ế");
        cs.put("`Ê", "Ề");
        cs.put("`^E", "Ề");
        cs.put("`ê", "ề");
        cs.put("`^e", "ề");
        cs.put("?Ê", "Ể");
        cs.put("?^E", "Ể");
        cs.put("?ê", "ể");
        cs.put("?^e", "ể");
        cs.put("~Ê", "Ễ");
        cs.put("~^E", "Ễ");
        cs.put("~ê", "ễ");
        cs.put("~^e", "ễ");
        cs.put("^!E", "Ệ");
        cs.put("^!e", "ệ");
        cs.put("?I", "Ỉ");
        cs.put("?i", "ỉ");
        cs.put("!I", "Ị");
        cs.put("!i", "ị");
        cs.put("!O", "Ọ");
        cs.put("!o", "ọ");
        cs.put("?O", "Ỏ");
        cs.put("?o", "ỏ");
        cs.put("'Ô", "Ố");
        cs.put("'^O", "Ố");
        cs.put("'ô", "ố");
        cs.put("'^o", "ố");
        cs.put("`Ô", "Ồ");
        cs.put("`^O", "Ồ");
        cs.put("`ô", "ồ");
        cs.put("`^o", "ồ");
        cs.put("?Ô", "Ổ");
        cs.put("?^O", "Ổ");
        cs.put("?ô", "ổ");
        cs.put("?^o", "ổ");
        cs.put("~Ô", "Ỗ");
        cs.put("~^O", "Ỗ");
        cs.put("~ô", "ỗ");
        cs.put("~^o", "ỗ");
        cs.put("^!O", "Ộ");
        cs.put("^!o", "ộ");
        cs.put("'Ơ", "Ớ");
        cs.put("'+O", "Ớ");
        cs.put("'ơ", "ớ");
        cs.put("'+o", "ớ");
        cs.put("`Ơ", "Ờ");
        cs.put("`+O", "Ờ");
        cs.put("`ơ", "ờ");
        cs.put("`+o", "ờ");
        cs.put("?Ơ", "Ở");
        cs.put("?+O", "Ở");
        cs.put("?ơ", "ở");
        cs.put("?+o", "ở");
        cs.put("~Ơ", "Ỡ");
        cs.put("~+O", "Ỡ");
        cs.put("~ơ", "ỡ");
        cs.put("~+o", "ỡ");
        cs.put("!Ơ", "Ợ");
        cs.put("!+O", "Ợ");
        cs.put("!ơ", "ợ");
        cs.put("!+o", "ợ");
        cs.put("!U", "Ụ");
        cs.put("!u", "ụ");
        cs.put("?U", "Ủ");
        cs.put("?u", "ủ");
        cs.put("'Ư", "Ứ");
        cs.put("'+U", "Ứ");
        cs.put("'ư", "ứ");
        cs.put("'+u", "ứ");
        cs.put("`Ư", "Ừ");
        cs.put("`+U", "Ừ");
        cs.put("`ư", "ừ");
        cs.put("`+u", "ừ");
        cs.put("?Ư", "Ử");
        cs.put("?+U", "Ử");
        cs.put("?ư", "ử");
        cs.put("?+u", "ử");
        cs.put("~Ư", "Ữ");
        cs.put("~+U", "Ữ");
        cs.put("~ư", "ữ");
        cs.put("~+u", "ữ");
        cs.put("!Ư", "Ự");
        cs.put("!+U", "Ự");
        cs.put("!ư", "ự");
        cs.put("!+u", "ự");
        cs.put("`Y", "Ỳ");
        cs.put("`y", "ỳ");
        cs.put("!Y", "Ỵ");
        cs.put("!y", "ỵ");
        cs.put("?Y", "Ỷ");
        cs.put("?y", "ỷ");
        cs.put("~Y", "Ỹ");
        cs.put("~y", "ỹ");
        cs.put("^0", "⁰");
        cs.put("^_i", "ⁱ");
        cs.put("^4", "⁴");
        cs.put("^5", "⁵");
        cs.put("^6", "⁶");
        cs.put("^7", "⁷");
        cs.put("^8", "⁸");
        cs.put("^9", "⁹");
        cs.put("^+", "⁺");
        cs.put("^=", "⁼");
        cs.put("^(", "⁽");
        cs.put("^)", "⁾");
        cs.put("^_n", "ⁿ");
        cs.put("_0", "₀");
        cs.put("_1", "₁");
        cs.put("_2", "₂");
        cs.put("_3", "₃");
        cs.put("_4", "₄");
        cs.put("_5", "₅");
        cs.put("_6", "₆");
        cs.put("_7", "₇");
        cs.put("_8", "₈");
        cs.put("_9", "₉");
        cs.put("_+", "₊");
        cs.put("_=", "₌");
        cs.put("_(", "₍");
        cs.put("_)", "₎");
        cs.put("SM", "℠");
        cs.put("sM", "℠");
        cs.put("Sm", "℠");
        cs.put("sm", "℠");
        cs.put("TM", "™");
        cs.put("tM", "™");
        cs.put("Tm", "™");
        cs.put("tm", "™");
        cs.put("13", "⅓");
        cs.put("23", "⅔");
        cs.put("15", "⅕");
        cs.put("25", "⅖");
        cs.put("35", "⅗");
        cs.put("45", "⅘");
        cs.put("16", "⅙");
        cs.put("56", "⅚");
        cs.put("18", "⅛");
        cs.put("38", "⅜");
        cs.put("58", "⅝");
        cs.put("78", "⅞");
        cs.put("/←", "↚");
        cs.put("/→", "↛");
        cs.put("<-", "←");
        cs.put("->", "→");
        cs.put("/=", "≠");
        cs.put("=/", "≠");
        cs.put("<=", "≤");
        cs.put(">=", "≥");
        cs.put("(1)", "①");
        cs.put("(2)", "②");
        cs.put("(3)", "③");
        cs.put("(4)", "④");
        cs.put("(5)", "⑤");
        cs.put("(6)", "⑥");
        cs.put("(7)", "⑦");
        cs.put("(8)", "⑧");
        cs.put("(9)", "⑨");
        cs.put("(10)", "⑩");
        cs.put("(11)", "⑪");
        cs.put("(12)", "⑫");
        cs.put("(13)", "⑬");
        cs.put("(14)", "⑭");
        cs.put("(15)", "⑮");
        cs.put("(16)", "⑯");
        cs.put("(17)", "⑰");
        cs.put("(18)", "⑱");
        cs.put("(19)", "⑲");
        cs.put("(20)", "⑳");
        cs.put("(A)", "Ⓐ");
        cs.put("(B)", "Ⓑ");
        cs.put("(C)", "Ⓒ");
        cs.put("(D)", "Ⓓ");
        cs.put("(E)", "Ⓔ");
        cs.put("(F)", "Ⓕ");
        cs.put("(G)", "Ⓖ");
        cs.put("(H)", "Ⓗ");
        cs.put("(I)", "Ⓘ");
        cs.put("(J)", "Ⓙ");
        cs.put("(K)", "Ⓚ");
        cs.put("(L)", "Ⓛ");
        cs.put("(M)", "Ⓜ");
        cs.put("(N)", "Ⓝ");
        cs.put("(O)", "Ⓞ");
        cs.put("(P)", "Ⓟ");
        cs.put("(Q)", "Ⓠ");
        cs.put("(R)", "Ⓡ");
        cs.put("(S)", "Ⓢ");
        cs.put("(T)", "Ⓣ");
        cs.put("(U)", "Ⓤ");
        cs.put("(V)", "Ⓥ");
        cs.put("(W)", "Ⓦ");
        cs.put("(X)", "Ⓧ");
        cs.put("(Y)", "Ⓨ");
        cs.put("(Z)", "Ⓩ");
        cs.put("(a)", "ⓐ");
        cs.put("(b)", "ⓑ");
        cs.put("(c)", "ⓒ");
        cs.put("(d)", "ⓓ");
        cs.put("(e)", "ⓔ");
        cs.put("(f)", "ⓕ");
        cs.put("(g)", "ⓖ");
        cs.put("(h)", "ⓗ");
        cs.put("(i)", "ⓘ");
        cs.put("(j)", "ⓙ");
        cs.put("(k)", "ⓚ");
        cs.put("(l)", "ⓛ");
        cs.put("(m)", "ⓜ");
        cs.put("(n)", "ⓝ");
        cs.put("(o)", "ⓞ");
        cs.put("(p)", "ⓟ");
        cs.put("(q)", "ⓠ");
        cs.put("(r)", "ⓡ");
        cs.put("(s)", "ⓢ");
        cs.put("(t)", "ⓣ");
        cs.put("(u)", "ⓤ");
        cs.put("(v)", "ⓥ");
        cs.put("(w)", "ⓦ");
        cs.put("(x)", "ⓧ");
        cs.put("(y)", "ⓨ");
        cs.put("(z)", "ⓩ");
        cs.put("(0)", "⓪");
        cs.put("(21)", "㉑");
        cs.put("(22)", "㉒");
        cs.put("(23)", "㉓");
        cs.put("(24)", "㉔");
        cs.put("(25)", "㉕");
        cs.put("(26)", "㉖");
        cs.put("(27)", "㉗");
        cs.put("(28)", "㉘");
        cs.put("(29)", "㉙");
        cs.put("(30)", "㉚");
        cs.put("(31)", "㉛");
        cs.put("(32)", "㉜");
        cs.put("(33)", "㉝");
        cs.put("(34)", "㉞");
        cs.put("(35)", "㉟");
        cs.put("(36)", "㊱");
        cs.put("(37)", "㊲");
        cs.put("(38)", "㊳");
        cs.put("(39)", "㊴");
        cs.put("(40)", "㊵");
        cs.put("(41)", "㊶");
        cs.put("(42)", "㊷");
        cs.put("(43)", "㊸");
        cs.put("(44)", "㊹");
        cs.put("(45)", "㊺");
        cs.put("(46)", "㊻");
        cs.put("(47)", "㊼");
        cs.put("(48)", "㊽");
        cs.put("(49)", "㊾");
        cs.put("(50)", "㊿");
        cs.put("\\o/", "🙌");
        composeSequences = cs;
    }

    private void doCompose(int code, int[] keyCodes) {

        if (mKeyboardSwitcher.getInputView().isShifted()
                && mKeyboardSwitcher.isAlphabetMode()
                && Character.isLowerCase(code)) {
            code = Character.toUpperCase(code);
        }
        composeBuffer += (char) code;
        updateShiftKeyState(getCurrentInputEditorInfo());

        String composed = composeSequences.get(composeBuffer);
        if (composed != null) {
            onText(composed);
//            for (int c = 0; c < composed.length(); ++c) {
//                handleCharacter(composed.charAt(c), keyCodes);
//            }
            compose = false;
            return;
        }

        // partial key matching... could be better?
        for (String key : composeSequences.keySet()) {
            if (key.startsWith(composeBuffer)) {
                return; // partial match found - continue compose sequence
            }
        }
        // No matching compose sequence
        // FIXME: beep
        compose = false;
    }

    // Implementation of KeyboardViewListener

    public void onKey(int primaryCode, int[] keyCodes, int x, int y) {
        long when = SystemClock.uptimeMillis();
        if (primaryCode != Keyboard.KEYCODE_DELETE
                || when > mLastKeyTime + QUICK_PRESS) {
            mDeleteCount = 0;
        }
        mLastKeyTime = when;
        final boolean distinctMultiTouch = mKeyboardSwitcher
                .hasDistinctMultitouch();
        switch (primaryCode) {
        case Keyboard.KEYCODE_DELETE:
            if (compose) {
                doCompose(primaryCode, keyCodes);
                break;
            }
            handleBackspace();
            mDeleteCount++;
            LatinImeLogger.logOnDelete();
            break;
        case Keyboard.KEYCODE_SHIFT:
            // Shift key is handled in onPress() when device has distinct
            // multi-touch panel.
            if (!distinctMultiTouch)
                handleShift();
            break;
        case Keyboard.KEYCODE_MODE_CHANGE:
            // Symbol key is handled in onPress() when device has distinct
            // multi-touch panel.
            if (!distinctMultiTouch)
                changeKeyboardMode();
            break;
        case LatinKeyboardView.KEYCODE_CTRL_LEFT:
            // Ctrl key is handled in onPress() when device has distinct
            // multi-touch panel.
            if (!distinctMultiTouch)
                setModCtrl(!mModCtrl);
            break;
        case LatinKeyboardView.KEYCODE_ALT_LEFT:
            // Alt key is handled in onPress() when device has distinct
            // multi-touch panel.
            if (!distinctMultiTouch)
                setModAlt(!mModAlt);
            break;
        case LatinKeyboardView.KEYCODE_FN:
            if (!distinctMultiTouch)
                setModFn(!mModFn);
            break;
        case Keyboard.KEYCODE_CANCEL:
            if (!isShowingOptionDialog()) {
                handleClose();
            }
            break;
        case LatinKeyboardView.KEYCODE_OPTIONS:
            onOptionKeyPressed();
            break;
        case LatinKeyboardView.KEYCODE_OPTIONS_LONGPRESS:
            onOptionKeyLongPressed();
            break;
        case LatinKeyboardView.KEYCODE_DPAD_CENTER_LONGPRESS:
            compose = !compose;
            composeBuffer = "";
            break;
        case LatinKeyboardView.KEYCODE_NEXT_LANGUAGE:
            toggleLanguage(false, true);
            break;
        case LatinKeyboardView.KEYCODE_PREV_LANGUAGE:
            toggleLanguage(false, false);
            break;
        case LatinKeyboardView.KEYCODE_VOICE:
            if (VOICE_INSTALLED) {
                startListening(false /* was a button press, was not a swipe */);
            }
            break;
        case 9 /* Tab */:
            if (compose) {
                doCompose(primaryCode, keyCodes);
                break;
            }
            sendModifierKeysDown(true);
            sendTab();
            sendModifierKeysUp(true);
            break;
        case LatinKeyboardView.KEYCODE_ESCAPE:
            if (compose) {
                doCompose(primaryCode, keyCodes);
                break;
            }
            sendModifierKeysDown(true);
            sendEscape();
            sendModifierKeysUp(true);
            break;
        case LatinKeyboardView.KEYCODE_DPAD_UP:
        case LatinKeyboardView.KEYCODE_DPAD_DOWN:
        case LatinKeyboardView.KEYCODE_DPAD_LEFT:
        case LatinKeyboardView.KEYCODE_DPAD_RIGHT:
        case LatinKeyboardView.KEYCODE_DPAD_CENTER:
        case LatinKeyboardView.KEYCODE_HOME:
        case LatinKeyboardView.KEYCODE_END:
        case LatinKeyboardView.KEYCODE_PAGE_UP:
        case LatinKeyboardView.KEYCODE_PAGE_DOWN:
        case LatinKeyboardView.KEYCODE_FKEY_F1:
        case LatinKeyboardView.KEYCODE_FKEY_F2:
        case LatinKeyboardView.KEYCODE_FKEY_F3:
        case LatinKeyboardView.KEYCODE_FKEY_F4:
        case LatinKeyboardView.KEYCODE_FKEY_F5:
        case LatinKeyboardView.KEYCODE_FKEY_F6:
        case LatinKeyboardView.KEYCODE_FKEY_F7:
        case LatinKeyboardView.KEYCODE_FKEY_F8:
        case LatinKeyboardView.KEYCODE_FKEY_F9:
        case LatinKeyboardView.KEYCODE_FKEY_F10:
        case LatinKeyboardView.KEYCODE_FKEY_F11:
        case LatinKeyboardView.KEYCODE_FKEY_F12:
        case LatinKeyboardView.KEYCODE_FORWARD_DEL:
        case LatinKeyboardView.KEYCODE_INSERT:
        case LatinKeyboardView.KEYCODE_SYSRQ:
        case LatinKeyboardView.KEYCODE_BREAK:
        case LatinKeyboardView.KEYCODE_NUM_LOCK:
        case LatinKeyboardView.KEYCODE_SCROLL_LOCK:
            if (compose) {
                doCompose(primaryCode, keyCodes);
                break;
            }
            // send as plain keys, or as escape sequence if needed
            sendModifierKeysDown(true);
            sendSpecialKey(primaryCode);
            sendModifierKeysUp(true);
            break;
        default:
            if (compose) {
                doCompose(primaryCode, keyCodes);
                break;
            }
            if (primaryCode != KEYCODE_ENTER) {
                mJustAddedAutoSpace = false;
            }
            RingCharBuffer.getInstance().push((char) primaryCode, x, y);
            LatinImeLogger.logOnInputChar();
            if (mModCtrl || mModAlt) {
                sendModifierKeysDown(false);
                sendCharWithModifiers(primaryCode, keyCodes);
                sendModifierKeysUp(false);
            } else if (isWordSeparator(primaryCode)) {
                handleSeparator(primaryCode);
            } else {
                handleCharacter(primaryCode, keyCodes);
            }
            // Cancel the just reverted state
            mJustRevertedSeparator = null;
        }
        mKeyboardSwitcher.onKey(primaryCode);
        // Reset after any single keystroke
        mEnteredText = null;
    }

    public void onText(CharSequence text) {
        if (VOICE_INSTALLED && mVoiceInputHighlighted) {
            commitVoiceInput();
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        abortCorrection(false);
        ic.beginBatchEdit();
        if (mPredicting) {
            commitTyped(ic);
        }
        maybeRemovePreviousPeriod(text);
        ic.commitText(text, 1);
        ic.endBatchEdit();
        updateShiftKeyState(getCurrentInputEditorInfo());
        mKeyboardSwitcher.onKey(0); // dummy key code.
        mJustRevertedSeparator = null;
        mJustAddedAutoSpace = false;
        mEnteredText = text;
    }

    public void onCancel() {
        // User released a finger outside any key
        mKeyboardSwitcher.onCancelInput();
    }

    private void handleBackspace() {
        if (VOICE_INSTALLED && mVoiceInputHighlighted) {
            mVoiceInput
                    .incrementTextModificationDeleteCount(mVoiceResults.candidates
                            .get(0).toString().length());
            revertVoiceInput();
            return;
        }
        boolean deleteChar = false;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;

        ic.beginBatchEdit();

        if (mAfterVoiceInput) {
            // Don't log delete if the user is pressing delete at
            // the beginning of the text box (hence not deleting anything)
            if (mVoiceInput.getCursorPos() > 0) {
                // If anything was selected before the delete was pressed,
                // increment the
                // delete count by the length of the selection
                int deleteLen = mVoiceInput.getSelectionSpan() > 0 ? mVoiceInput
                        .getSelectionSpan()
                        : 1;
                mVoiceInput.incrementTextModificationDeleteCount(deleteLen);
            }
        }

        if (mPredicting) {
            final int length = mComposing.length();
            if (length > 0) {
                mComposing.delete(length - 1, length);
                mWord.deleteLast();
                ic.setComposingText(mComposing, 1);
                if (mComposing.length() == 0) {
                    mPredicting = false;
                }
                postUpdateSuggestions();
            } else {
                ic.deleteSurroundingText(1, 0);
            }
        } else {
            deleteChar = true;
        }
        postUpdateShiftKeyState();
        TextEntryState.backspace();
        if (TextEntryState.getState() == TextEntryState.State.UNDO_COMMIT) {
            revertLastWord(deleteChar);
            ic.endBatchEdit();
            return;
        } else if (mEnteredText != null
                && sameAsTextBeforeCursor(ic, mEnteredText)) {
            ic.deleteSurroundingText(mEnteredText.length(), 0);
        } else if (deleteChar) {
            if (mCandidateView != null
                    && mCandidateView.dismissAddToDictionaryHint()) {
                // Go back to the suggestion mode if the user canceled the
                // "Touch again to save".
                // NOTE: In gerenal, we don't revert the word when backspacing
                // from a manual suggestion pick. We deliberately chose a
                // different behavior only in the case of picking the first
                // suggestion (typed word). It's intentional to have made this
                // inconsistent with backspacing after selecting other
                // suggestions.
                revertLastWord(deleteChar);
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                if (mDeleteCount > DELETE_ACCELERATE_AT) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                }
            }
        }
        mJustRevertedSeparator = null;
        ic.endBatchEdit();
    }

    private void setModShift(boolean val) {
        mModShift = val;
    }

    private void setModCtrl(boolean val) {
        // Log.i("LatinIME", "setModCtrl "+ mModCtrl + "->" + val + ", momentary=" + mCtrlKeyState.isMomentary());
        mModCtrl = val;
    }

    private void setModAlt(boolean val) {
        // Log.i("LatinIME", "setModAlt "+ mModAlt + "->" + val + ", momentary=" + mAltKeyState.isMomentary());
        mModAlt = val;
    }

    private void setModFn(boolean val) {
        //Log.i("LatinIME", "setModFn " + mModFn + "->" + val + ", momentary=" + mFnKeyState.isMomentary());
        mModFn = val;
        mKeyboardSwitcher.setFn(val);
    }

    private void resetShift() {
        handleShiftInternal(true);
    }

    private void handleShift() {
        handleShiftInternal(false);
    }

    private void handleShiftInternal(boolean forceNormal) {
        //Log.i(TAG, "handleShiftInternal " + forceNormal);
        mHandler.removeMessages(MSG_UPDATE_SHIFT_STATE);
        KeyboardSwitcher switcher = mKeyboardSwitcher;
        LatinKeyboardView inputView = switcher.getInputView();
        if (switcher.isAlphabetMode()) {
            if (mCapsLock || forceNormal) {
                mCapsLock = false;
                switcher.setShifted(false);
            } else if (inputView != null) {
                if (inputView.isShifted()) {
                    mCapsLock = true;
                    switcher.setShiftLocked(true);
                } else {
                    switcher.setShifted(true);
                }
            }
        } else {
            switcher.toggleShift();
        }
    }

    private void abortCorrection(boolean force) {
        if (force || TextEntryState.isCorrecting()) {
            getCurrentInputConnection().finishComposingText();
            clearSuggestions();
        }
    }

    private void handleCharacter(int primaryCode, int[] keyCodes) {
        if (VOICE_INSTALLED && mVoiceInputHighlighted) {
            commitVoiceInput();
        }

        if (mAfterVoiceInput) {
            // Assume input length is 1. This assumption fails for smiley face
            // insertions.
            mVoiceInput.incrementTextModificationInsertCount(1);
        }
        if (mLastSelectionStart == mLastSelectionEnd
                && TextEntryState.isCorrecting()) {
            abortCorrection(false);
        }

        if (isAlphabet(primaryCode) && isPredictionOn()
                && !isCursorTouchingWord()) {
            if (!mPredicting) {
                mPredicting = true;
                mComposing.setLength(0);
                saveWordInHistory(mBestWord);
                mWord.reset();
            }
        }
        if (mKeyboardSwitcher.getInputView().isShifted()) {
            if (keyCodes == null || keyCodes[0] < Character.MIN_CODE_POINT
                    || keyCodes[0] > Character.MAX_CODE_POINT) {
                return;
            }
            primaryCode = keyCodes[0];
            if (mKeyboardSwitcher.isAlphabetMode()
                    && Character.isLowerCase(primaryCode)) {
                int upperCaseCode = Character.toUpperCase(primaryCode);
                if (upperCaseCode != primaryCode) {
                    primaryCode = upperCaseCode;
                } else {
                    // Some keys, such as [eszett], have upper case as
                    // multi-characters.
                    String upperCase = new String(new int[] { primaryCode }, 0,
                            1).toUpperCase();
                    onText(upperCase);
                    return;
                }
            }
        }
        if (mPredicting) {
            if (mKeyboardSwitcher.getInputView().isShifted()
                    && mKeyboardSwitcher.isAlphabetMode()
                    && mComposing.length() == 0) {
                mWord.setFirstCharCapitalized(true);
            }
            mComposing.append((char) primaryCode);
            mWord.add(primaryCode, keyCodes);
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                // If it's the first letter, make note of auto-caps state
                if (mWord.size() == 1) {
                    mWord.setAutoCapitalized(getCursorCapsMode(ic,
                            getCurrentInputEditorInfo()) != 0);
                }
                ic.setComposingText(mComposing, 1);
            }
            postUpdateSuggestions();
        } else {
            sendKeyChar((char) primaryCode);
        }
        updateShiftKeyState(getCurrentInputEditorInfo());
        if (LatinIME.PERF_DEBUG)
            measureCps();
        TextEntryState.typedCharacter((char) primaryCode,
                isWordSeparator(primaryCode));
    }

    private void handleSeparator(int primaryCode) {
        if (VOICE_INSTALLED && mVoiceInputHighlighted) {
            commitVoiceInput();
        }

        if (mAfterVoiceInput) {
            // Assume input length is 1. This assumption fails for smiley face
            // insertions.
            mVoiceInput.incrementTextModificationInsertPunctuationCount(1);
        }

        // Should dismiss the "Touch again to save" message when handling
        // separator
        if (mCandidateView != null
                && mCandidateView.dismissAddToDictionaryHint()) {
            postUpdateSuggestions();
        }

        boolean pickedDefault = false;
        // Handle separator
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.beginBatchEdit();
            abortCorrection(false);
        }
        if (mPredicting) {
            // In certain languages where single quote is a separator, it's
            // better
            // not to auto correct, but accept the typed word. For instance,
            // in Italian dov' should not be expanded to dove' because the
            // elision
            // requires the last vowel to be removed.
            if (mAutoCorrectOn
                    && primaryCode != '\''
                    && (mJustRevertedSeparator == null
                            || mJustRevertedSeparator.length() == 0 || mJustRevertedSeparator
                            .charAt(0) != primaryCode)) {
                pickedDefault = pickDefaultSuggestion();
                // Picked the suggestion by the space key. We consider this
                // as "added an auto space".
                if (primaryCode == KEYCODE_SPACE) {
                    mJustAddedAutoSpace = true;
                }
            } else {
                commitTyped(ic);
            }
        }
        if (mJustAddedAutoSpace && primaryCode == KEYCODE_ENTER) {
            removeTrailingSpace();
            mJustAddedAutoSpace = false;
        }
        sendKeyChar((char) primaryCode);

        // Handle the case of ". ." -> " .." with auto-space if necessary
        // before changing the TextEntryState.
        if (TextEntryState.getState() == TextEntryState.State.PUNCTUATION_AFTER_ACCEPTED
                && primaryCode == KEYCODE_PERIOD) {
            reswapPeriodAndSpace();
        }

        TextEntryState.typedCharacter((char) primaryCode, true);
        if (TextEntryState.getState() == TextEntryState.State.PUNCTUATION_AFTER_ACCEPTED
                && primaryCode != KEYCODE_ENTER) {
            swapPunctuationAndSpace();
        } else if (isPredictionOn() && primaryCode == KEYCODE_SPACE) {
            doubleSpace();
        }
        if (pickedDefault) {
            TextEntryState.backToAcceptedDefault(mWord.getTypedWord());
        }
        updateShiftKeyState(getCurrentInputEditorInfo());
        if (ic != null) {
            ic.endBatchEdit();
        }
    }

    private void handleClose() {
        commitTyped(getCurrentInputConnection());
        if (VOICE_INSTALLED & mRecognizing) {
            mVoiceInput.cancel();
        }
        requestHideSelf(0);
        if (mKeyboardSwitcher != null) {
            LatinKeyboardView inputView = mKeyboardSwitcher.getInputView();
            if (inputView != null) {
                inputView.closing();
            }
        }
        TextEntryState.endSession();
    }

    private void saveWordInHistory(CharSequence result) {
        if (mWord.size() <= 1) {
            mWord.reset();
            return;
        }
        // Skip if result is null. It happens in some edge case.
        if (TextUtils.isEmpty(result)) {
            return;
        }

        // Make a copy of the CharSequence, since it is/could be a mutable
        // CharSequence
        final String resultCopy = result.toString();
        TypedWordAlternatives entry = new TypedWordAlternatives(resultCopy,
                new WordComposer(mWord));
        mWordHistory.add(entry);
    }

    private void postUpdateSuggestions() {
        mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS);
        mHandler.sendMessageDelayed(mHandler
                .obtainMessage(MSG_UPDATE_SUGGESTIONS), 100);
    }

    private void postUpdateOldSuggestions() {
        mHandler.removeMessages(MSG_UPDATE_OLD_SUGGESTIONS);
        mHandler.sendMessageDelayed(mHandler
                .obtainMessage(MSG_UPDATE_OLD_SUGGESTIONS), 300);
    }

    private boolean isPredictionOn() {
        return mPredictionOn;
    }

    private boolean isCandidateStripVisible() {
        return isPredictionOn() && mShowSuggestions;
    }

    public void onCancelVoice() {
        if (mRecognizing) {
            switchToKeyboardView();
        }
    }

    private void switchToKeyboardView() {
        mHandler.post(new Runnable() {
            public void run() {
                mRecognizing = false;
                if (mKeyboardSwitcher.getInputView() != null) {
                    setInputView(mKeyboardSwitcher.getInputView());
                }
                setCandidatesViewShown(true);
                updateInputViewShown();
                postUpdateSuggestions();
            }
        });
    }

    private void switchToRecognitionStatusView() {
        final boolean configChanged = mConfigurationChanging;
        mHandler.post(new Runnable() {
            public void run() {
                setCandidatesViewShown(false);
                mRecognizing = true;
                View v = mVoiceInput.getView();
                ViewParent p = v.getParent();
                if (p != null && p instanceof ViewGroup) {
                    ((ViewGroup) v.getParent()).removeView(v);
                }
                setInputView(v);
                updateInputViewShown();
                if (configChanged) {
                    mVoiceInput.onConfigurationChanged();
                }
            }
        });
    }

    private void startListening(boolean swipe) {
        if (!mHasUsedVoiceInput
                || (!mLocaleSupportedForVoiceInput && !mHasUsedVoiceInputUnsupportedLocale)) {
            // Calls reallyStartListening if user clicks OK, does nothing if
            // user clicks Cancel.
            showVoiceWarningDialog(swipe);
        } else {
            reallyStartListening(swipe);
        }
    }

    private void reallyStartListening(boolean swipe) {
        if (!mHasUsedVoiceInput) {
            // The user has started a voice input, so remember that in the
            // future (so we don't show the warning dialog after the first run).
            SharedPreferences.Editor editor = PreferenceManager
                    .getDefaultSharedPreferences(this).edit();
            editor.putBoolean(PREF_HAS_USED_VOICE_INPUT, true);
            SharedPreferencesCompat.apply(editor);
            mHasUsedVoiceInput = true;
        }

        if (!mLocaleSupportedForVoiceInput
                && !mHasUsedVoiceInputUnsupportedLocale) {
            // The user has started a voice input from an unsupported locale, so
            // remember that
            // in the future (so we don't show the warning dialog the next time
            // they do this).
            SharedPreferences.Editor editor = PreferenceManager
                    .getDefaultSharedPreferences(this).edit();
            editor.putBoolean(PREF_HAS_USED_VOICE_INPUT_UNSUPPORTED_LOCALE,
                    true);
            SharedPreferencesCompat.apply(editor);
            mHasUsedVoiceInputUnsupportedLocale = true;
        }

        // Clear N-best suggestions
        clearSuggestions();

        FieldContext context = new FieldContext(getCurrentInputConnection(),
                getCurrentInputEditorInfo(), mLanguageSwitcher
                        .getInputLanguage(), mLanguageSwitcher
                        .getEnabledLanguages());
        mVoiceInput.startListening(context, swipe);
        switchToRecognitionStatusView();
    }

    private void showVoiceWarningDialog(final boolean swipe) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setIcon(R.drawable.ic_mic_dialog);
        builder.setPositiveButton(android.R.string.ok,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        mVoiceInput.logKeyboardWarningDialogOk();
                        reallyStartListening(swipe);
                    }
                });
        builder.setNegativeButton(android.R.string.cancel,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        mVoiceInput.logKeyboardWarningDialogCancel();
                    }
                });

        if (mLocaleSupportedForVoiceInput) {
            String message = getString(R.string.voice_warning_may_not_understand)
                    + "\n\n"
                    + getString(R.string.voice_warning_how_to_turn_off);
            builder.setMessage(message);
        } else {
            String message = getString(R.string.voice_warning_locale_not_supported)
                    + "\n\n"
                    + getString(R.string.voice_warning_may_not_understand)
                    + "\n\n"
                    + getString(R.string.voice_warning_how_to_turn_off);
            builder.setMessage(message);
        }

        builder.setTitle(R.string.voice_warning_title);
        mVoiceWarningDialog = builder.create();

        Window window = mVoiceWarningDialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = mKeyboardSwitcher.getInputView().getWindowToken();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mVoiceInput.logKeyboardWarningDialogShown();
        mVoiceWarningDialog.show();
    }

    public void onVoiceResults(List<String> candidates,
            Map<String, List<CharSequence>> alternatives) {
        if (!mRecognizing) {
            return;
        }
        mVoiceResults.candidates = candidates;
        mVoiceResults.alternatives = alternatives;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_VOICE_RESULTS));
    }

    private void handleVoiceResults() {
        mAfterVoiceInput = true;
        mImmediatelyAfterVoiceInput = true;

        InputConnection ic = getCurrentInputConnection();
        if (!isFullscreenMode()) {
            // Start listening for updates to the text from typing, etc.
            if (ic != null) {
                ExtractedTextRequest req = new ExtractedTextRequest();
                ic.getExtractedText(req,
                        InputConnection.GET_EXTRACTED_TEXT_MONITOR);
            }
        }

        vibrate();
        switchToKeyboardView();

        final List<CharSequence> nBest = new ArrayList<CharSequence>();
        boolean capitalizeFirstWord = preferCapitalization()
                || (mKeyboardSwitcher.isAlphabetMode() && mKeyboardSwitcher
                        .getInputView().isShifted());
        for (String c : mVoiceResults.candidates) {
            if (capitalizeFirstWord) {
                c = Character.toUpperCase(c.charAt(0))
                        + c.substring(1, c.length());
            }
            nBest.add(c);
        }

        if (nBest.size() == 0) {
            return;
        }

        String bestResult = nBest.get(0).toString();

        mVoiceInput.logVoiceInputDelivered(bestResult.length());

        mHints.registerVoiceResult(bestResult);

        if (ic != null)
            ic.beginBatchEdit(); // To avoid extra updates on committing older
                                 // text

        commitTyped(ic);
        EditingUtil.appendText(ic, bestResult);

        if (ic != null)
            ic.endBatchEdit();

        mVoiceInputHighlighted = true;
        mWordToSuggestions.putAll(mVoiceResults.alternatives);
    }

    private void clearSuggestions() {
        setSuggestions(null, false, false, false);
    }

    private void setSuggestions(List<CharSequence> suggestions,
            boolean completions, boolean typedWordValid,
            boolean haveMinimalSuggestion) {

        if (mIsShowingHint) {
            setCandidatesView(mCandidateViewContainer);
            mIsShowingHint = false;
        }

        if (mCandidateView != null) {
            mCandidateView.setSuggestions(suggestions, completions,
                    typedWordValid, haveMinimalSuggestion);
        }
    }

    private void updateSuggestions() {
        LatinKeyboardView inputView = mKeyboardSwitcher.getInputView();
        ((LatinKeyboard) inputView.getKeyboard()).setPreferredLetters(null);

        // Check if we have a suggestion engine attached.
        if ((mSuggest == null || !isPredictionOn()) && !mVoiceInputHighlighted) {
            return;
        }

        if (!mPredicting) {
            setNextSuggestions();
            return;
        }
        showSuggestions(mWord);
    }

    private List<CharSequence> getTypedSuggestions(WordComposer word) {
        List<CharSequence> stringList = mSuggest.getSuggestions(
                mKeyboardSwitcher.getInputView(), word, false, null);
        return stringList;
    }

    private void showCorrections(WordAlternatives alternatives) {
        List<CharSequence> stringList = alternatives.getAlternatives();
        ((LatinKeyboard) mKeyboardSwitcher.getInputView().getKeyboard())
                .setPreferredLetters(null);
        showSuggestions(stringList, alternatives.getOriginalWord(), false,
                false);
    }

    private void showSuggestions(WordComposer word) {
        // long startTime = System.currentTimeMillis(); // TIME MEASUREMENT!
        // TODO Maybe need better way of retrieving previous word
        CharSequence prevWord = EditingUtil.getPreviousWord(
                getCurrentInputConnection(), mWordSeparators);
        List<CharSequence> stringList = mSuggest.getSuggestions(
                mKeyboardSwitcher.getInputView(), word, false, prevWord);
        // long stopTime = System.currentTimeMillis(); // TIME MEASUREMENT!
        // Log.d("LatinIME","Suggest Total Time - " + (stopTime - startTime));

        int[] nextLettersFrequencies = mSuggest.getNextLettersFrequencies();

        ((LatinKeyboard) mKeyboardSwitcher.getInputView().getKeyboard())
                .setPreferredLetters(nextLettersFrequencies);

        boolean correctionAvailable = !mInputTypeNoAutoCorrect
                && mSuggest.hasMinimalCorrection();
        // || mCorrectionMode == mSuggest.CORRECTION_FULL;
        CharSequence typedWord = word.getTypedWord();
        // If we're in basic correct
        boolean typedWordValid = mSuggest.isValidWord(typedWord)
                || (preferCapitalization() && mSuggest.isValidWord(typedWord
                        .toString().toLowerCase()));
        if (mCorrectionMode == Suggest.CORRECTION_FULL
                || mCorrectionMode == Suggest.CORRECTION_FULL_BIGRAM) {
            correctionAvailable |= typedWordValid;
        }
        // Don't auto-correct words with multiple capital letter
        correctionAvailable &= !word.isMostlyCaps();
        correctionAvailable &= !TextEntryState.isCorrecting();

        showSuggestions(stringList, typedWord, typedWordValid,
                correctionAvailable);
    }

    private void showSuggestions(List<CharSequence> stringList,
            CharSequence typedWord, boolean typedWordValid,
            boolean correctionAvailable) {
        setSuggestions(stringList, false, typedWordValid, correctionAvailable);
        if (stringList.size() > 0) {
            if (correctionAvailable && !typedWordValid && stringList.size() > 1) {
                mBestWord = stringList.get(1);
            } else {
                mBestWord = typedWord;
            }
        } else {
            mBestWord = null;
        }
        setCandidatesViewShown(isCandidateStripVisible() || mCompletionOn);
    }

    private boolean pickDefaultSuggestion() {
        // Complete any pending candidate query first
        if (mHandler.hasMessages(MSG_UPDATE_SUGGESTIONS)) {
            mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS);
            updateSuggestions();
        }
        if (mBestWord != null && mBestWord.length() > 0) {
            TextEntryState.acceptedDefault(mWord.getTypedWord(), mBestWord);
            mJustAccepted = true;
            pickSuggestion(mBestWord, false);
            // Add the word to the auto dictionary if it's not a known word
            addToDictionaries(mBestWord, AutoDictionary.FREQUENCY_FOR_TYPED);
            return true;

        }
        return false;
    }

    public void pickSuggestionManually(int index, CharSequence suggestion) {
        List<CharSequence> suggestions = mCandidateView.getSuggestions();

        if (mAfterVoiceInput && mShowingVoiceSuggestions) {
            mVoiceInput.flushAllTextModificationCounters();
            // send this intent AFTER logging any prior aggregated edits.
            mVoiceInput.logTextModifiedByChooseSuggestion(
                    suggestion.toString(), index, mWordSeparators,
                    getCurrentInputConnection());
        }

        final boolean correcting = TextEntryState.isCorrecting();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.beginBatchEdit();
        }
        if (mCompletionOn && mCompletions != null && index >= 0
                && index < mCompletions.length) {
            CompletionInfo ci = mCompletions[index];
            if (ic != null) {
                ic.commitCompletion(ci);
            }
            mCommittedLength = suggestion.length();
            if (mCandidateView != null) {
                mCandidateView.clear();
            }
            updateShiftKeyState(getCurrentInputEditorInfo());
            if (ic != null) {
                ic.endBatchEdit();
            }
            return;
        }

        // If this is a punctuation, apply it through the normal key press
        if (suggestion.length() == 1
                && (isWordSeparator(suggestion.charAt(0)) || isSuggestedPunctuation(suggestion
                        .charAt(0)))) {
            // Word separators are suggested before the user inputs something.
            // So, LatinImeLogger logs "" as a user's input.
            LatinImeLogger.logOnManualSuggestion("", suggestion.toString(),
                    index, suggestions);
            final char primaryCode = suggestion.charAt(0);
            onKey(primaryCode, new int[] { primaryCode },
                    LatinKeyboardBaseView.NOT_A_TOUCH_COORDINATE,
                    LatinKeyboardBaseView.NOT_A_TOUCH_COORDINATE);
            if (ic != null) {
                ic.endBatchEdit();
            }
            return;
        }
        mJustAccepted = true;
        pickSuggestion(suggestion, correcting);
        // Add the word to the auto dictionary if it's not a known word
        if (index == 0) {
            addToDictionaries(suggestion, AutoDictionary.FREQUENCY_FOR_PICKED);
        } else {
            addToBigramDictionary(suggestion, 1);
        }
        LatinImeLogger.logOnManualSuggestion(mComposing.toString(), suggestion
                .toString(), index, suggestions);
        TextEntryState.acceptedSuggestion(mComposing.toString(), suggestion);
        // Follow it with a space
        if (mAutoSpace && !correcting) {
            sendSpace();
            mJustAddedAutoSpace = true;
        }

        final boolean showingAddToDictionaryHint = index == 0
                && mCorrectionMode > 0 && !mSuggest.isValidWord(suggestion)
                && !mSuggest.isValidWord(suggestion.toString().toLowerCase());

        if (!correcting) {
            // Fool the state watcher so that a subsequent backspace will not do
            // a revert, unless
            // we just did a correction, in which case we need to stay in
            // TextEntryState.State.PICKED_SUGGESTION state.
            TextEntryState.typedCharacter((char) KEYCODE_SPACE, true);
            setNextSuggestions();
        } else if (!showingAddToDictionaryHint) {
            // If we're not showing the "Touch again to save", then show
            // corrections again.
            // In case the cursor position doesn't change, make sure we show the
            // suggestions again.
            clearSuggestions();
            postUpdateOldSuggestions();
        }
        if (showingAddToDictionaryHint) {
            mCandidateView.showAddToDictionaryHint(suggestion);
        }
        if (ic != null) {
            ic.endBatchEdit();
        }
    }

    private void rememberReplacedWord(CharSequence suggestion) {
        if (mShowingVoiceSuggestions) {
            // Retain the replaced word in the alternatives array.
            EditingUtil.Range range = new EditingUtil.Range();
            String wordToBeReplaced = EditingUtil.getWordAtCursor(
                    getCurrentInputConnection(), mWordSeparators, range);
            if (!mWordToSuggestions.containsKey(wordToBeReplaced)) {
                wordToBeReplaced = wordToBeReplaced.toLowerCase();
            }
            if (mWordToSuggestions.containsKey(wordToBeReplaced)) {
                List<CharSequence> suggestions = mWordToSuggestions
                        .get(wordToBeReplaced);
                if (suggestions.contains(suggestion)) {
                    suggestions.remove(suggestion);
                }
                suggestions.add(wordToBeReplaced);
                mWordToSuggestions.remove(wordToBeReplaced);
                mWordToSuggestions.put(suggestion.toString(), suggestions);
            }
        }
    }

    /**
     * Commits the chosen word to the text field and saves it for later
     * retrieval.
     *
     * @param suggestion
     *            the suggestion picked by the user to be committed to the text
     *            field
     * @param correcting
     *            whether this is due to a correction of an existing word.
     */
    private void pickSuggestion(CharSequence suggestion, boolean correcting) {
        LatinKeyboardView inputView = mKeyboardSwitcher.getInputView();
        if (mCapsLock) {
            suggestion = suggestion.toString().toUpperCase();
        } else if (preferCapitalization()
                || (mKeyboardSwitcher.isAlphabetMode() && inputView.isShifted()
                        && !(mKeyboardSwitcher.isFullMode() && !correcting))) {
            suggestion = suggestion.toString().toUpperCase().charAt(0)
                    + suggestion.subSequence(1, suggestion.length()).toString();
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            rememberReplacedWord(suggestion);
            ic.commitText(suggestion, 1);
        }
        saveWordInHistory(suggestion);
        mPredicting = false;
        mCommittedLength = suggestion.length();
        ((LatinKeyboard) inputView.getKeyboard()).setPreferredLetters(null);
        // If we just corrected a word, then don't show punctuations
        if (!correcting) {
            setNextSuggestions();
        }
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    /**
     * Tries to apply any voice alternatives for the word if this was a spoken
     * word and there are voice alternatives.
     *
     * @param touching
     *            The word that the cursor is touching, with position
     *            information
     * @return true if an alternative was found, false otherwise.
     */
    private boolean applyVoiceAlternatives(EditingUtil.SelectedWord touching) {
        // Search for result in spoken word alternatives
        String selectedWord = touching.word.toString().trim();
        if (!mWordToSuggestions.containsKey(selectedWord)) {
            selectedWord = selectedWord.toLowerCase();
        }
        if (mWordToSuggestions.containsKey(selectedWord)) {
            mShowingVoiceSuggestions = true;
            List<CharSequence> suggestions = mWordToSuggestions
                    .get(selectedWord);
            // If the first letter of touching is capitalized, make all the
            // suggestions
            // start with a capital letter.
            if (Character.isUpperCase(touching.word.charAt(0))) {
                for (int i = 0; i < suggestions.size(); i++) {
                    String origSugg = (String) suggestions.get(i);
                    String capsSugg = origSugg.toUpperCase().charAt(0)
                            + origSugg.subSequence(1, origSugg.length())
                                    .toString();
                    suggestions.set(i, capsSugg);
                }
            }
            setSuggestions(suggestions, false, true, true);
            setCandidatesViewShown(true);
            return true;
        }
        return false;
    }

    /**
     * Tries to apply any typed alternatives for the word if we have any cached
     * alternatives, otherwise tries to find new corrections and completions for
     * the word.
     *
     * @param touching
     *            The word that the cursor is touching, with position
     *            information
     * @return true if an alternative was found, false otherwise.
     */
    private boolean applyTypedAlternatives(EditingUtil.SelectedWord touching) {
        // If we didn't find a match, search for result in typed word history
        WordComposer foundWord = null;
        WordAlternatives alternatives = null;
        for (WordAlternatives entry : mWordHistory) {
            if (TextUtils.equals(entry.getChosenWord(), touching.word)) {
                if (entry instanceof TypedWordAlternatives) {
                    foundWord = ((TypedWordAlternatives) entry).word;
                }
                alternatives = entry;
                break;
            }
        }
        // If we didn't find a match, at least suggest completions
        if (foundWord == null
                && (mSuggest.isValidWord(touching.word) || mSuggest
                        .isValidWord(touching.word.toString().toLowerCase()))) {
            foundWord = new WordComposer();
            for (int i = 0; i < touching.word.length(); i++) {
                foundWord.add(touching.word.charAt(i),
                        new int[] { touching.word.charAt(i) });
            }
            foundWord.setFirstCharCapitalized(Character
                    .isUpperCase(touching.word.charAt(0)));
        }
        // Found a match, show suggestions
        if (foundWord != null || alternatives != null) {
            if (alternatives == null) {
                alternatives = new TypedWordAlternatives(touching.word,
                        foundWord);
            }
            showCorrections(alternatives);
            if (foundWord != null) {
                mWord = new WordComposer(foundWord);
            } else {
                mWord.reset();
            }
            return true;
        }
        return false;
    }

    private void setOldSuggestions() {
        mShowingVoiceSuggestions = false;
        if (mCandidateView != null
                && mCandidateView.isShowingAddToDictionaryHint()) {
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return;
        if (!mPredicting) {
            // Extract the selected or touching text
            EditingUtil.SelectedWord touching = EditingUtil
                    .getWordAtCursorOrSelection(ic, mLastSelectionStart,
                            mLastSelectionEnd, mWordSeparators);

            if (touching != null && touching.word.length() > 1) {
                ic.beginBatchEdit();

                if (!applyVoiceAlternatives(touching)
                        && !applyTypedAlternatives(touching)) {
                    abortCorrection(true);
                } else {
                    TextEntryState.selectedForCorrection();
                    EditingUtil.underlineWord(ic, touching);
                }

                ic.endBatchEdit();
            } else {
                abortCorrection(true);
                setNextSuggestions(); // Show the punctuation suggestions list
            }
        } else {
            abortCorrection(true);
        }
    }

    private void setNextSuggestions() {
        setSuggestions(mSuggestPuncList, false, false, false);
    }

    private void addToDictionaries(CharSequence suggestion, int frequencyDelta) {
        checkAddToDictionary(suggestion, frequencyDelta, false);
    }

    private void addToBigramDictionary(CharSequence suggestion,
            int frequencyDelta) {
        checkAddToDictionary(suggestion, frequencyDelta, true);
    }

    /**
     * Adds to the UserBigramDictionary and/or AutoDictionary
     *
     * @param addToBigramDictionary
     *            true if it should be added to bigram dictionary if possible
     */
    private void checkAddToDictionary(CharSequence suggestion,
            int frequencyDelta, boolean addToBigramDictionary) {
        if (suggestion == null || suggestion.length() < 1)
            return;
        // Only auto-add to dictionary if auto-correct is ON. Otherwise we'll be
        // adding words in situations where the user or application really
        // didn't
        // want corrections enabled or learned.
        if (!(mCorrectionMode == Suggest.CORRECTION_FULL || mCorrectionMode == Suggest.CORRECTION_FULL_BIGRAM)) {
            return;
        }
        if (suggestion != null) {
            if (!addToBigramDictionary
                    && mAutoDictionary.isValidWord(suggestion)
                    || (!mSuggest.isValidWord(suggestion.toString()) && !mSuggest
                            .isValidWord(suggestion.toString().toLowerCase()))) {
                mAutoDictionary.addWord(suggestion.toString(), frequencyDelta);
            }

            if (mUserBigramDictionary != null) {
                CharSequence prevWord = EditingUtil.getPreviousWord(
                        getCurrentInputConnection(), mSentenceSeparators);
                if (!TextUtils.isEmpty(prevWord)) {
                    mUserBigramDictionary.addBigrams(prevWord.toString(),
                            suggestion.toString());
                }
            }
        }
    }

    private boolean isCursorTouchingWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null)
            return false;
        CharSequence toLeft = ic.getTextBeforeCursor(1, 0);
        CharSequence toRight = ic.getTextAfterCursor(1, 0);
        if (!TextUtils.isEmpty(toLeft) && !isWordSeparator(toLeft.charAt(0))
                && !isSuggestedPunctuation(toLeft.charAt(0))) {
            return true;
        }
        if (!TextUtils.isEmpty(toRight) && !isWordSeparator(toRight.charAt(0))
                && !isSuggestedPunctuation(toRight.charAt(0))) {
            return true;
        }
        return false;
    }

    private boolean sameAsTextBeforeCursor(InputConnection ic, CharSequence text) {
        CharSequence beforeText = ic.getTextBeforeCursor(text.length(), 0);
        return TextUtils.equals(text, beforeText);
    }

    public void revertLastWord(boolean deleteChar) {
        final int length = mComposing.length();
        if (!mPredicting && length > 0) {
            final InputConnection ic = getCurrentInputConnection();
            mPredicting = true;
            mJustRevertedSeparator = ic.getTextBeforeCursor(1, 0);
            if (deleteChar)
                ic.deleteSurroundingText(1, 0);
            int toDelete = mCommittedLength;
            CharSequence toTheLeft = ic
                    .getTextBeforeCursor(mCommittedLength, 0);
            if (toTheLeft != null && toTheLeft.length() > 0
                    && isWordSeparator(toTheLeft.charAt(0))) {
                toDelete--;
            }
            ic.deleteSurroundingText(toDelete, 0);
            ic.setComposingText(mComposing, 1);
            TextEntryState.backspace();
            postUpdateSuggestions();
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            mJustRevertedSeparator = null;
        }
    }

    protected String getWordSeparators() {
        return mWordSeparators;
    }

    public boolean isWordSeparator(int code) {
        String separators = getWordSeparators();
        return separators.contains(String.valueOf((char) code));
    }

    private boolean isSentenceSeparator(int code) {
        return mSentenceSeparators.contains(String.valueOf((char) code));
    }

    private void sendSpace() {
        sendKeyChar((char) KEYCODE_SPACE);
        updateShiftKeyState(getCurrentInputEditorInfo());
        // onKey(KEY_SPACE[0], KEY_SPACE);
    }

    public boolean preferCapitalization() {
        return mWord.isFirstCharCapitalized();
    }

    void toggleLanguage(boolean reset, boolean next) {
        if (reset) {
            mLanguageSwitcher.reset();
        } else {
            if (next) {
                mLanguageSwitcher.next();
            } else {
                mLanguageSwitcher.prev();
            }
        }
        int currentKeyboardMode = mKeyboardSwitcher.getKeyboardMode();
        reloadKeyboards();
        mKeyboardSwitcher.makeKeyboards(true);
        mKeyboardSwitcher.setKeyboardMode(currentKeyboardMode, 0,
                mEnableVoiceButton && mEnableVoice);
        initSuggest(mLanguageSwitcher.getInputLanguage());
        mLanguageSwitcher.persist();
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
            String key) {
        Log.i("PCKeyboard", "onSharedPreferenceChanged()");
        boolean needReload = false;
        Resources res = getResources();
        if (PREF_SELECTED_LANGUAGES.equals(key)) {
            mLanguageSwitcher.loadLocales(sharedPreferences);
            mRefreshKeyboardRequired = true;
        } else if (PREF_RECORRECTION_ENABLED.equals(key)) {
            mReCorrectionEnabled = sharedPreferences.getBoolean(
                    PREF_RECORRECTION_ENABLED, res
                            .getBoolean(R.bool.default_recorrection_enabled));
            if (mReCorrectionEnabled) {
                // It doesn't work right on pre-Gingerbread phones.
                Toast.makeText(getApplicationContext(),
                        res.getString(R.string.recorrect_warning), Toast.LENGTH_LONG)
                        .show();
            }
        } else if (PREF_CONNECTBOT_TAB_HACK.equals(key)) {
            mConnectbotTabHack = sharedPreferences.getBoolean(
                    PREF_CONNECTBOT_TAB_HACK, res
                            .getBoolean(R.bool.default_connectbot_tab_hack));
        } else if (PREF_FULLSCREEN_OVERRIDE.equals(key)) {
            mFullscreenOverride = sharedPreferences.getBoolean(
                    PREF_FULLSCREEN_OVERRIDE, res
                            .getBoolean(R.bool.default_fullscreen_override));
        } else if (PREF_FULL_KEYBOARD_IN_PORTRAIT.equals(key)) {
            mFullInPortrait = sharedPreferences.getBoolean(
                    PREF_FULL_KEYBOARD_IN_PORTRAIT, res
                            .getBoolean(R.bool.default_full_in_portrait));
            needReload = true;
        } else if (PREF_SUGGESTIONS_IN_LANDSCAPE.equals(key)) {
            mSuggestionsInLandscape = sharedPreferences.getBoolean(
                    PREF_SUGGESTIONS_IN_LANDSCAPE, res
                            .getBoolean(R.bool.default_suggestions_in_landscape));
            needReload = true;
        } else if (PREF_HEIGHT_PORTRAIT.equals(key)) {
            mHeightPortrait = getHeight(sharedPreferences,
                    PREF_HEIGHT_PORTRAIT, res.getString(R.string.default_height_portrait));
            needReload = true;
        } else if (PREF_HEIGHT_LANDSCAPE.equals(key)) {
            mHeightLandscape = getHeight(sharedPreferences,
                    PREF_HEIGHT_LANDSCAPE, res.getString(R.string.default_height_landscape));
            needReload = true;
        } else if (PREF_HINT_MODE.equals(key)) {
            mHintMode = Integer.parseInt(sharedPreferences.getString(PREF_HINT_MODE,
                    res.getString(R.string.default_hint_mode)));
            needReload = true;
        }

        mKeyboardSwitcher.setFullKeyboardOptions(mFullInPortrait,
                mHeightPortrait, mHeightLandscape, mHintMode);
        if (needReload) {
            mKeyboardSwitcher.makeKeyboards(true);
        }
    }

    public void swipeRight() {
        if (LatinKeyboardView.DEBUG_AUTO_PLAY) {
            ClipboardManager cm = ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE));
            CharSequence text = cm.getText();
            if (!TextUtils.isEmpty(text)) {
                mKeyboardSwitcher.getInputView().startPlaying(text.toString());
            }
        }
    }

    public void swipeLeft() {
    }

    public void swipeDown() {
        handleClose();
    }

    public void swipeUp() {
        // launchSettings();
    }

    public void onPress(int primaryCode) {
        InputConnection ic = getCurrentInputConnection();
        if (mKeyboardSwitcher.isVibrateAndSoundFeedbackRequired()) {
            vibrate();
            playKeyClick(primaryCode);
        }
        final boolean distinctMultiTouch = mKeyboardSwitcher
                .hasDistinctMultitouch();
        if (distinctMultiTouch && primaryCode == Keyboard.KEYCODE_SHIFT) {
            mShiftKeyState.onPress();
            handleShift();
            setModShift(!mModShift);
            // sendKeyDown(ic, KeyEvent.KEYCODE_SHIFT_LEFT); // disabled, issue #24
        } else if (distinctMultiTouch
                && primaryCode == Keyboard.KEYCODE_MODE_CHANGE) {
            changeKeyboardMode();
            mSymbolKeyState.onPress();
            mKeyboardSwitcher.setAutoModeSwitchStateMomentary();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_CTRL_LEFT) {
            setModCtrl(!mModCtrl);
            mCtrlKeyState.onPress();
            sendKeyDown(ic, 113); // KeyEvent.KEYCODE_CTRL_LEFT
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_ALT_LEFT) {
            setModAlt(!mModAlt);
            mAltKeyState.onPress();
            sendKeyDown(ic, 57); // KeyEvent.KEYCODE_ALT_LEFT
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_FN) {
            setModFn(!mModFn);
            mFnKeyState.onPress();
        } else {
            mShiftKeyState.onOtherKeyPressed();
            mSymbolKeyState.onOtherKeyPressed();
            mCtrlKeyState.onOtherKeyPressed();
            mAltKeyState.onOtherKeyPressed();
            mFnKeyState.onOtherKeyPressed();
        }
    }

    public void onRelease(int primaryCode) {
        // Reset any drag flags in the keyboard
        ((LatinKeyboard) mKeyboardSwitcher.getInputView().getKeyboard())
                .keyReleased();
        // vibrate();
        final boolean distinctMultiTouch = mKeyboardSwitcher
                .hasDistinctMultitouch();
        InputConnection ic = getCurrentInputConnection();
        if (distinctMultiTouch && primaryCode == Keyboard.KEYCODE_SHIFT) {
            if (mShiftKeyState.isMomentary())
                resetShift();
            //sendKeyUp(ic, KeyEvent.KEYCODE_SHIFT_LEFT); // disabled, issue #24
            setModShift(false);
            mShiftKeyState.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == Keyboard.KEYCODE_MODE_CHANGE) {
            // Snap back to the previous keyboard mode if the user chords the
            // mode change key and
            // other key, then released the mode change key.
            if (mKeyboardSwitcher.isInChordingAutoModeSwitchState())
                changeKeyboardMode();
            mSymbolKeyState.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_CTRL_LEFT) {
            if (mCtrlKeyState.isMomentary()) {
                setModCtrl(false);
            }
            sendKeyUp(ic, 113); // KeyEvent.KEYCODE_CTRL_LEFT
            mCtrlKeyState.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_ALT_LEFT) {
            if (mAltKeyState.isMomentary()) {
                setModAlt(false);
            }
            sendKeyUp(ic, 57); // KeyEvent.KEYCODE_ALT_LEFT
            mAltKeyState.onRelease();
        } else if (distinctMultiTouch
                && primaryCode == LatinKeyboardView.KEYCODE_FN) {
            if (mFnKeyState.isMomentary()) {
                setModFn(false);
            }
            mFnKeyState.onRelease();
        }
    }

    private FieldContext makeFieldContext() {
        return new FieldContext(getCurrentInputConnection(),
                getCurrentInputEditorInfo(), mLanguageSwitcher
                        .getInputLanguage(), mLanguageSwitcher
                        .getEnabledLanguages());
    }

    private boolean fieldCanDoVoice(FieldContext fieldContext) {
        return !mPasswordText && mVoiceInput != null
                && !mVoiceInput.isBlacklistedField(fieldContext);
    }

    private boolean shouldShowVoiceButton(FieldContext fieldContext,
            EditorInfo attribute) {
        return ENABLE_VOICE_BUTTON
                && fieldCanDoVoice(fieldContext)
                && !(attribute != null && IME_OPTION_NO_MICROPHONE
                        .equals(attribute.privateImeOptions))
                && SpeechRecognizer.isRecognitionAvailable(this);
    }

    // receive ringer mode changes to detect silent mode
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateRingerMode();
        }
    };

    // update flags for silent mode
    private void updateRingerMode() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (mAudioManager != null) {
            mSilentMode = (mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL);
        }
    }

    private void playKeyClick(int primaryCode) {
        // if mAudioManager is null, we don't have the ringer state yet
        // mAudioManager will be set by updateRingerMode
        if (mAudioManager == null) {
            if (mKeyboardSwitcher.getInputView() != null) {
                updateRingerMode();
            }
        }
        if (mSoundOn && !mSilentMode) {
            // FIXME: Volume and enable should come from UI settings
            // FIXME: These should be triggered after auto-repeat logic
            int sound = AudioManager.FX_KEYPRESS_STANDARD;
            switch (primaryCode) {
            case Keyboard.KEYCODE_DELETE:
                sound = AudioManager.FX_KEYPRESS_DELETE;
                break;
            case KEYCODE_ENTER:
                sound = AudioManager.FX_KEYPRESS_RETURN;
                break;
            case KEYCODE_SPACE:
                sound = AudioManager.FX_KEYPRESS_SPACEBAR;
                break;
            }
            mAudioManager.playSoundEffect(sound, FX_VOLUME);
        }
    }

    private void vibrate() {
        if (!mVibrateOn) {
            return;
        }
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(mVibrateLen);
            return;
        }

        if (mKeyboardSwitcher.getInputView() != null) {
            mKeyboardSwitcher.getInputView().performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    private void checkTutorial(String privateImeOptions) {
        if (privateImeOptions == null)
            return;
        if (privateImeOptions.equals("com.android.setupwizard:ShowTutorial")) {
            if (mTutorial == null)
                startTutorial();
        } else if (privateImeOptions
                .equals("com.android.setupwizard:HideTutorial")) {
            if (mTutorial != null) {
                if (mTutorial.close()) {
                    mTutorial = null;
                }
            }
        }
    }

    private void startTutorial() {
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_START_TUTORIAL),
                500);
    }

    /* package */void tutorialDone() {
        mTutorial = null;
    }

    /* package */void promoteToUserDictionary(String word, int frequency) {
        if (mUserDictionary.isValidWord(word))
            return;
        mUserDictionary.addWord(word, frequency);
    }

    /* package */WordComposer getCurrentWord() {
        return mWord;
    }

    /* package */boolean getPopupOn() {
        return mPopupOn;
    }

    private void updateCorrectionMode() {
        mHasDictionary = mSuggest != null ? mSuggest.hasMainDictionary()
                : false;
        mAutoCorrectOn = (mAutoCorrectEnabled || mQuickFixes)
                && !mInputTypeNoAutoCorrect && mHasDictionary;
        mCorrectionMode = (mAutoCorrectOn && mAutoCorrectEnabled) ? Suggest.CORRECTION_FULL
                : (mAutoCorrectOn ? Suggest.CORRECTION_BASIC
                        : Suggest.CORRECTION_NONE);
        mCorrectionMode = (mBigramSuggestionEnabled && mAutoCorrectOn && mAutoCorrectEnabled) ? Suggest.CORRECTION_FULL_BIGRAM
                : mCorrectionMode;
        if (suggestionsDisabled()) {
            mAutoCorrectOn = false;
            mCorrectionMode = Suggest.CORRECTION_NONE;
        }
        if (mSuggest != null) {
            mSuggest.setCorrectionMode(mCorrectionMode);
        }
    }

    private void updateAutoTextEnabled(Locale systemLocale) {
        if (mSuggest == null)
            return;
        boolean different = !systemLocale.getLanguage().equalsIgnoreCase(
                mInputLocale.substring(0, 2));
        mSuggest.setAutoTextEnabled(!different && mQuickFixes);
    }

    protected void launchSettings() {
        launchSettings(LatinIMESettings.class);
    }

    public void launchDebugSettings() {
        launchSettings(LatinIMEDebugSettings.class);
    }

    protected void launchSettings(
            Class<? extends PreferenceActivity> settingsClass) {
        handleClose();
        Intent intent = new Intent();
        intent.setClass(LatinIME.this, settingsClass);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void loadSettings() {
        // Get the settings preferences
        SharedPreferences sp = PreferenceManager
                .getDefaultSharedPreferences(this);
        mVibrateOn = sp.getBoolean(PREF_VIBRATE_ON, false);
        mVibrateLen = getPrefInt(sp, PREF_VIBRATE_LEN, getResources().getString(R.string.vibrate_duration_ms));
        mSoundOn = sp.getBoolean(PREF_SOUND_ON, false);
        mPopupOn = sp.getBoolean(PREF_POPUP_ON, mResources
                .getBoolean(R.bool.default_popup_preview));
        mAutoCap = sp.getBoolean(PREF_AUTO_CAP, getResources().getBoolean(
                R.bool.default_auto_cap));
        mQuickFixes = sp.getBoolean(PREF_QUICK_FIXES, true);
        mHasUsedVoiceInput = sp.getBoolean(PREF_HAS_USED_VOICE_INPUT, false);
        mHasUsedVoiceInputUnsupportedLocale = sp.getBoolean(
                PREF_HAS_USED_VOICE_INPUT_UNSUPPORTED_LOCALE, false);

        // Get the current list of supported locales and check the current
        // locale against that
        // list. We cache this value so as not to check it every time the user
        // starts a voice
        // input. Because this method is called by onStartInputView, this should
        // mean that as
        // long as the locale doesn't change while the user is keeping the IME
        // open, the
        // value should never be stale.
        String supportedLocalesString = SettingsUtil.getSettingsString(
                getContentResolver(),
                SettingsUtil.LATIN_IME_VOICE_INPUT_SUPPORTED_LOCALES,
                DEFAULT_VOICE_INPUT_SUPPORTED_LOCALES);
        ArrayList<String> voiceInputSupportedLocales = newArrayList(supportedLocalesString
                .split("\\s+"));

        mLocaleSupportedForVoiceInput = voiceInputSupportedLocales
                .contains(mInputLocale);

        mShowSuggestions = sp.getBoolean(PREF_SHOW_SUGGESTIONS, mResources
                .getBoolean(R.bool.default_suggestions));

        if (VOICE_INSTALLED) {
            final String voiceMode = sp.getString(PREF_VOICE_MODE,
                    getString(R.string.voice_mode_main));
            boolean enableVoice = !voiceMode
                    .equals(getString(R.string.voice_mode_off))
                    && mEnableVoiceButton;
            boolean voiceOnPrimary = voiceMode
                    .equals(getString(R.string.voice_mode_main));
            if (mKeyboardSwitcher != null
                    && (enableVoice != mEnableVoice || voiceOnPrimary != mVoiceOnPrimary)) {
                mKeyboardSwitcher.setVoiceMode(enableVoice, voiceOnPrimary);
            }
            mEnableVoice = enableVoice;
            mVoiceOnPrimary = voiceOnPrimary;
        }
        mAutoCorrectEnabled = sp.getBoolean(PREF_AUTO_COMPLETE, mResources
                .getBoolean(R.bool.enable_autocorrect))
                & mShowSuggestions;
        // mBigramSuggestionEnabled = sp.getBoolean(
        // PREF_BIGRAM_SUGGESTIONS, true) & mShowSuggestions;
        updateCorrectionMode();
        updateAutoTextEnabled(mResources.getConfiguration().locale);
        mLanguageSwitcher.loadLocales(sp);
    }

    private void initSuggestPuncList() {
        mSuggestPuncList = new ArrayList<CharSequence>();
        mSuggestPuncs = mResources.getString(R.string.suggested_punctuations);
        if (mSuggestPuncs != null) {
            for (int i = 0; i < mSuggestPuncs.length(); i++) {
                mSuggestPuncList.add(mSuggestPuncs.subSequence(i, i + 1));
            }
        }
    }

    private boolean isSuggestedPunctuation(int code) {
        return mSuggestPuncs.contains(String.valueOf((char) code));
    }

    private void showOptionsMenu() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setIcon(R.drawable.ic_dialog_keyboard);
        builder.setNegativeButton(android.R.string.cancel, null);
        CharSequence itemSettings = getString(R.string.english_ime_settings);
        CharSequence itemInputMethod = getString(R.string.selectInputMethod);
        builder.setItems(new CharSequence[] { itemInputMethod, itemSettings },
                new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface di, int position) {
                        di.dismiss();
                        switch (position) {
                        case POS_SETTINGS:
                            launchSettings();
                            break;
                        case POS_METHOD:
                            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                                    .showInputMethodPicker();
                            break;
                        }
                    }
                });
        builder.setTitle(mResources
                .getString(R.string.english_ime_input_options));
        mOptionsDialog = builder.create();
        Window window = mOptionsDialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = mKeyboardSwitcher.getInputView().getWindowToken();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mOptionsDialog.show();
    }

    public void changeKeyboardMode() {
        mKeyboardSwitcher.toggleSymbols();
        if (mCapsLock && mKeyboardSwitcher.isAlphabetMode()) {
            mKeyboardSwitcher.setShiftLocked(mCapsLock);
        }

        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    public static <E> ArrayList<E> newArrayList(E... elements) {
        int capacity = (elements.length * 110) / 100 + 5;
        ArrayList<E> list = new ArrayList<E>(capacity);
        Collections.addAll(list, elements);
        return list;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        super.dump(fd, fout, args);

        final Printer p = new PrintWriterPrinter(fout);
        p.println("LatinIME state :");
        p.println("  Keyboard mode = " + mKeyboardSwitcher.getKeyboardMode());
        p.println("  mCapsLock=" + mCapsLock);
        p.println("  mComposing=" + mComposing.toString());
        p.println("  mPredictionOn=" + mPredictionOn);
        p.println("  mCorrectionMode=" + mCorrectionMode);
        p.println("  mPredicting=" + mPredicting);
        p.println("  mAutoCorrectOn=" + mAutoCorrectOn);
        p.println("  mAutoSpace=" + mAutoSpace);
        p.println("  mCompletionOn=" + mCompletionOn);
        p.println("  TextEntryState.state=" + TextEntryState.getState());
        p.println("  mSoundOn=" + mSoundOn);
        p.println("  mVibrateOn=" + mVibrateOn);
        p.println("  mPopupOn=" + mPopupOn);
    }

    // Characters per second measurement

    private long mLastCpsTime;
    private static final int CPS_BUFFER_SIZE = 16;
    private long[] mCpsIntervals = new long[CPS_BUFFER_SIZE];
    private int mCpsIndex;
    private static Pattern NUMBER_RE = Pattern.compile("(\\d+).*");

    private void measureCps() {
        long now = System.currentTimeMillis();
        if (mLastCpsTime == 0)
            mLastCpsTime = now - 100; // Initial
        mCpsIntervals[mCpsIndex] = now - mLastCpsTime;
        mLastCpsTime = now;
        mCpsIndex = (mCpsIndex + 1) % CPS_BUFFER_SIZE;
        long total = 0;
        for (int i = 0; i < CPS_BUFFER_SIZE; i++)
            total += mCpsIntervals[i];
        System.out.println("CPS = " + ((CPS_BUFFER_SIZE * 1000f) / total));
    }

    public void onAutoCompletionStateChanged(boolean isAutoCompletion) {
        mKeyboardSwitcher.onAutoCompletionStateChanged(isAutoCompletion);
    }

    static int getIntFromString(String val, int defVal) {
        Matcher num = NUMBER_RE.matcher(val);
        if (!num.matches()) return defVal;
        return Integer.parseInt(num.group(1));
    }

    static int getPrefInt(SharedPreferences prefs, String prefName, int defVal) {
        String prefVal = prefs.getString(prefName, Integer.toString(defVal));
        //Log.i("PCKeyboard", "getPrefInt " + prefName + " = " + prefVal + ", default " + defVal);
        return getIntFromString(prefVal, defVal);
    }

    static int getPrefInt(SharedPreferences prefs, String prefName, String defStr) {
        int defVal = getIntFromString(defStr, 0);
        return getPrefInt(prefs, prefName, defVal);
    }

    static int getHeight(SharedPreferences prefs, String prefName, String defVal) {
        int val = getPrefInt(prefs, prefName, defVal);
        if (val < 15)
            val = 15;
        if (val > 75)
            val = 75;
        return val;
    }
}
