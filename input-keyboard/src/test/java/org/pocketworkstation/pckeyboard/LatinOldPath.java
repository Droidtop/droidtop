package org.pocketworkstation.pckeyboard;

import java.util.List;

/**
 * Hacker's Latin path as LatinIME had it before the engine seam (Droidtop/tracker#903 H6), copied here word for word so
 * LatinEngineTest runs it beside {@link LatinEngine}: only LatinIME's fields became parameters and mSuggest the
 * {@link LatinEngine.Lexicon} it now is. Each method names the LatinIME method it was.
 */
final class LatinOldPath {
    private LatinOldPath() {}

    // LatinIME.suggestionsDisabled()
    static boolean suggestionsDisabled(boolean mSuggestionForceOff, boolean mSuggestionForceOn,
            boolean mSuggestionsInLandscape, boolean portrait) {
        if (mSuggestionForceOff) return true;
        if (mSuggestionForceOn) return false;
        return !(mSuggestionsInLandscape || portrait);
    }

    // LatinIME.isPredictionWanted()
    static boolean isPredictionWanted(boolean mShowSuggestions, boolean mSuggestionForceOn, boolean suggestionsDisabled) {
        return (mShowSuggestions || mSuggestionForceOn) && !suggestionsDisabled;
    }

    // LatinIME.isPredictionOn()
    static boolean isPredictionOn(boolean mPredictionOnForMode, boolean isPredictionWanted) {
        return mPredictionOnForMode && isPredictionWanted;
    }

    /** What LatinIME.showSuggestions(WordComposer) passed on to showSuggestions(list, typed, valid, correction). */
    static final class Shown {
        List<CharSequence> stringList;
        CharSequence typedWord;
        boolean typedWordValid;
        boolean correctionAvailable;
        int[] nextLettersFrequencies;
        CharSequence mBestWord;
    }

    // LatinIME.showSuggestions(WordComposer word), then showSuggestions(List, CharSequence, boolean, boolean)'s mBestWord.
    static Shown showSuggestions(LatinEngine.Lexicon mSuggest, WordComposer word, CharSequence prevWord,
            boolean mInputTypeNoAutoCorrect, int mCorrectionMode, boolean isCorrecting, WordComposer mWord) {
        List<CharSequence> stringList = mSuggest.getSuggestions(
                null, word, false, prevWord);

        int[] nextLettersFrequencies = mSuggest.getNextLettersFrequencies();

        boolean correctionAvailable = !mInputTypeNoAutoCorrect
                && mSuggest.hasMinimalCorrection();
        // || mCorrectionMode == mSuggest.CORRECTION_FULL;
        CharSequence typedWord = word.getTypedWord();
        // If we're in basic correct
        boolean typedWordValid = mSuggest.isValidWord(typedWord)
                || (mWord.isFirstCharCapitalized() && mSuggest.isValidWord(typedWord
                        .toString().toLowerCase()));
        if (mCorrectionMode == Suggest.CORRECTION_FULL
                || mCorrectionMode == Suggest.CORRECTION_FULL_BIGRAM) {
            correctionAvailable |= typedWordValid;
        }
        // Don't auto-correct words with multiple capital letter
        correctionAvailable &= !word.isMostlyCaps();
        correctionAvailable &= !isCorrecting;

        Shown out = new Shown();
        out.stringList = stringList;
        out.typedWord = typedWord;
        out.typedWordValid = typedWordValid;
        out.correctionAvailable = correctionAvailable;
        out.nextLettersFrequencies = nextLettersFrequencies;
        CharSequence mBestWord;
        if (stringList.size() > 0) {
            if (correctionAvailable && !typedWordValid && stringList.size() > 1) {
                mBestWord = stringList.get(1);
            } else {
                mBestWord = typedWord;
            }
        } else {
            mBestWord = null;
        }
        out.mBestWord = mBestWord;
        return out;
    }

    /** LatinIME's composing state before the seam: mComposing, mWord and mPredicting, and what it did to them. */
    static final class Composing {
        final StringBuilder mComposing = new StringBuilder();
        WordComposer mWord = new WordComposer();
        boolean mPredicting;

        // handleCharacter, starting a word.
        void start() {
            mPredicting = true;
            mComposing.setLength(0);
            mWord.reset();
        }

        // handleCharacter, while predicting.
        void type(int primaryCode, int[] keyCodes) {
            if (mPredicting) {
                mComposing.append((char) primaryCode);
                mWord.add(primaryCode, keyCodes);
            }
        }

        // handleBackspace, while predicting.
        void backspace() {
            if (mPredicting) {
                final int length = mComposing.length();
                if (length > 0) {
                    mComposing.delete(length - 1, length);
                    mWord.deleteLast();
                    if (mComposing.length() == 0) {
                        mPredicting = false;
                    }
                }
            }
        }

        // commitTyped: what it committed, or null.
        CharSequence commitTyped() {
            if (mPredicting) {
                mPredicting = false;
                if (mComposing.length() > 0) {
                    return mComposing;
                }
            }
            return null;
        }

        // resetPrediction (and onUpdateSelection's cursor move).
        void reset() {
            mComposing.setLength(0);
            mPredicting = false;
        }
    }
}
