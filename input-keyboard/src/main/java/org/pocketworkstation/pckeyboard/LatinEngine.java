package org.pocketworkstation.pckeyboard;

import android.view.View;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * droidtop (Droidtop/tracker#903 H6; docs/SPEC.md 6a, "The engine seam"): Hacker's Keyboard's own Latin typing as the
 * first {@link InputEngine}. It wraps what was already there and rewrites none of it: the word being typed
 * ({@link WordComposer} and the composing text), whether a word is being composed, and the dictionaries' suggestions
 * ({@link Suggest}, through {@link Lexicon}). The decisions moved here from LatinIME are kept word for word
 * (LatinEngineTest runs the old code beside them): when Hacker's composes at all ({@link #suggestionsDisabled},
 * {@link #predictionWanted}, {@link #predictionOn}) and what the suggestion strip offers ({@link #suggest},
 * {@link #bestWord}). LatinIME keeps the rest of Hacker's typing (capitals, autocorrect at a separator, the word
 * history, revert), reading and writing {@link #composing}, {@link #word} and {@link #predicting} where it read its own
 * fields before.
 */
public final class LatinEngine implements InputEngine {

    /** Where the composing rules read the field and the settings: LatinIME's own flags, unchanged. */
    public interface Host {
        /** The field allows suggestions (Hacker's mPredictionOnForMode: not a password, address, URI or filter). */
        boolean predictionOnForMode();
        /** "Show suggestions" (Hacker's PREF_SHOW_SUGGESTIONS). */
        boolean showSuggestions();
        /** The suggestion toggle forced them on, or off, for this field. */
        boolean suggestionForceOn();
        boolean suggestionForceOff();
        /** "Suggestions in landscape". */
        boolean suggestionsInLandscape();
        boolean portrait();
        /** Hacker's correction mode ({@link Suggest#CORRECTION_NONE} and up). */
        int correctionMode();
    }

    /** The dictionaries, as the suggestion strip asks them: {@link Suggest}'s own methods. */
    public interface Lexicon {
        List<CharSequence> getSuggestions(View view, WordComposer wordComposer,
                boolean includeTypedWordIfValid, CharSequence prevWordForBigram);
        int[] getNextLettersFrequencies();
        boolean hasMinimalCorrection();
        boolean isValidWord(CharSequence word);
    }

    /** What the strip shows for the word being typed, and how the strip draws it. */
    public static final class Suggestions {
        public final List<CharSequence> words;
        public final CharSequence typedWord;
        public final boolean typedWordValid;
        public final boolean correctionAvailable;
        public final int[] nextLetters;

        Suggestions(List<CharSequence> words, CharSequence typedWord, boolean typedWordValid,
                boolean correctionAvailable, int[] nextLetters) {
            this.words = words;
            this.typedWord = typedWord;
            this.typedWordValid = typedWordValid;
            this.correctionAvailable = correctionAvailable;
            this.nextLetters = nextLetters;
        }
    }

    /** The text being composed (Hacker's mComposing). Kept after a commit, as Hacker's keeps it, for revertLastWord. */
    final StringBuilder composing = new StringBuilder();
    /** The word being typed with each key's neighbours (Hacker's mWord). */
    WordComposer word = new WordComposer();
    /** Whether a word is being composed (Hacker's mPredicting). */
    boolean predicting;

    private final Host mHost;
    private Lexicon mLexicon;
    private List<CharSequence> mShown = Collections.emptyList();
    private final InputEngine.Composing mComposingView = new InputEngine.Composing(composing,
            Collections.<InputEngine.Segment>emptyList(), -1, -1);

    public LatinEngine(Host host) {
        mHost = host;
    }

    /** The language's dictionaries ({@link Suggest}), set again when the language changes. */
    void setLexicon(Lexicon lexicon) {
        mLexicon = lexicon;
    }

    // The rules for when Hacker's composes, moved from LatinIME unchanged.

    /** Hacker's suggestionsDisabled(): forced off, else forced on, else only in portrait or with "in landscape" on. */
    static boolean suggestionsDisabled(boolean forceOff, boolean forceOn, boolean inLandscape, boolean portrait) {
        if (forceOff) return true;
        if (forceOn) return false;
        return !(inLandscape || portrait);
    }

    /** Hacker's isPredictionWanted(). */
    static boolean predictionWanted(boolean showSuggestions, boolean forceOn, boolean disabled) {
        return (showSuggestions || forceOn) && !disabled;
    }

    /** Hacker's isPredictionOn(). */
    static boolean predictionOn(boolean forMode, boolean wanted) {
        return forMode && wanted;
    }

    boolean suggestionsDisabled() {
        return suggestionsDisabled(mHost.suggestionForceOff(), mHost.suggestionForceOn(),
                mHost.suggestionsInLandscape(), mHost.portrait());
    }

    boolean predictionWanted() {
        return predictionWanted(mHost.showSuggestions(), mHost.suggestionForceOn(), suggestionsDisabled());
    }

    // What the strip offers, moved from LatinIME.showSuggestions unchanged.

    /**
     * The suggestions for [word] after [prevWord], and whether the strip may correct: LatinIME.showSuggestions(WordComposer)
     * as it was, with what it read from LatinIME passed in.
     */
    static Suggestions suggest(Lexicon lexicon, View view, WordComposer word, CharSequence prevWord,
            boolean inputTypeNoAutoCorrect, int correctionMode, boolean correcting, boolean preferCapitalization) {
        List<CharSequence> stringList = lexicon.getSuggestions(view, word, false, prevWord);
        int[] nextLettersFrequencies = lexicon.getNextLettersFrequencies();
        boolean correctionAvailable = !inputTypeNoAutoCorrect
                && lexicon.hasMinimalCorrection();
        CharSequence typedWord = word.getTypedWord();
        // If we're in basic correct
        boolean typedWordValid = lexicon.isValidWord(typedWord)
                || (preferCapitalization && lexicon.isValidWord(typedWord
                        .toString().toLowerCase()));
        if (correctionMode == Suggest.CORRECTION_FULL
                || correctionMode == Suggest.CORRECTION_FULL_BIGRAM) {
            correctionAvailable |= typedWordValid;
        }
        // Don't auto-correct words with multiple capital letter
        correctionAvailable &= !word.isMostlyCaps();
        correctionAvailable &= !correcting;
        return new Suggestions(stringList, typedWord, typedWordValid, correctionAvailable, nextLettersFrequencies);
    }

    /** The word a separator types (Hacker's mBestWord): the first correction when one is offered, else the typed word. */
    static CharSequence bestWord(List<CharSequence> stringList, CharSequence typedWord,
            boolean typedWordValid, boolean correctionAvailable) {
        if (stringList.size() > 0) {
            if (correctionAvailable && !typedWordValid && stringList.size() > 1) {
                return stringList.get(1);
            } else {
                return typedWord;
            }
        }
        return null;
    }

    /** The suggestions for the word being typed, from this engine's dictionaries; remembered for {@link #candidates}. */
    Suggestions suggest(View view, CharSequence prevWord, boolean inputTypeNoAutoCorrect, boolean correcting) {
        Suggestions s = suggest(mLexicon, view, word, prevWord, inputTypeNoAutoCorrect,
                mHost.correctionMode(), correcting, word.isFirstCharCapitalized());
        mShown = s.words;
        return s;
    }

    // InputEngine.

    @Override
    public Set<InputEngine.Capability> getCapabilities() {
        Set<InputEngine.Capability> out = EnumSet.noneOf(InputEngine.Capability.class);
        if (composes()) {
            out.add(InputEngine.Capability.PREDICTS);
            if (mHost.correctionMode() > Suggest.CORRECTION_NONE) out.add(InputEngine.Capability.CORRECTS);
        }
        return out;
    }

    /** Hacker's isPredictionOn(): no composing where it had none (suggestions off, a password, the terminal preset). */
    @Override
    public boolean composes() {
        return predictionOn(mHost.predictionOnForMode(), predictionWanted());
    }

    /** Hacker's strip shows exactly when it composes (isCandidateStripVisible()). */
    @Override
    public boolean wantsCandidateBar() {
        return composes();
    }

    @Override
    public void startField(boolean suggestionsAllowed, boolean password) {
        // The Host reads LatinIME's own flags for the field, so nothing is kept here.
    }

    /** Adds the key to the word while one is being composed (LatinIME starts one, as Hacker's did). Commits nothing. */
    @Override
    public InputEngine.KeyResult onChar(int code, int[] nearby) {
        if (!predicting) return InputEngine.KeyResult.NOT_HANDLED;
        composing.append((char) code);
        word.add(code, nearby);
        return InputEngine.KeyResult.HANDLED;
    }

    @Override
    public boolean onDelete() {
        final int length = composing.length();
        if (!predicting || length == 0) return false;
        composing.delete(length - 1, length);
        word.deleteLast();
        return true;
    }

    /**
     * Space and enter are Hacker's separators, handled by its own path (autocorrect, double space). The other engine
     * keys never reach it: LatinIME sends them only through an EngineSession, which the Latin path never has.
     */
    @Override
    public InputEngine.KeyResult onKey(InputEngine.Key key) {
        return InputEngine.KeyResult.NOT_HANDLED;
    }

    @Override
    public InputEngine.Composing composing() {
        return predicting ? mComposingView : InputEngine.Composing.EMPTY;
    }

    @Override
    public InputEngine.Candidates candidates() {
        return InputEngine.Candidates.of(mShown);
    }

    /** The word shown at [index]; composing ends, nothing else is deleted. */
    @Override
    public InputEngine.Picked pick(int index) {
        if (index < 0 || index >= mShown.size()) return null;
        predicting = false;
        return new InputEngine.Picked(mShown.get(index).toString(), false, 0);
    }

    /** Ends composing and gives the word as typed. The text is kept, as Hacker's keeps it, for a revert. */
    @Override
    public CharSequence commit() {
        if (!predicting) return "";
        predicting = false;
        return composing;
    }

    @Override
    public void reset() {
        composing.setLength(0);
        predicting = false;
    }
}
