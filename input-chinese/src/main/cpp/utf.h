// UTF-8 and UTF-16, for droidtop's JNI over librime (rime_jni.cc; Droidtop/tracker#903 H8b). librime's offsets count
// UTF-8 bytes and Android's count UTF-16 chars. Malformed UTF-8 decodes one byte at a time as U+FFFD, so a bad byte
// never swallows what follows it, and an offset inside a character counts that whole character.

#ifndef DROIDTOP_RIME_UTF_H_
#define DROIDTOP_RIME_UTF_H_

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace droidtop {

// Decodes the character at text[at], of at most `length - at` bytes: its code point, and how many bytes it took.
inline char32_t DecodeUtf8(const char* text, size_t length, size_t at, size_t* used) {
  const unsigned char lead = static_cast<unsigned char>(text[at]);
  size_t need;
  char32_t cp;
  if (lead < 0x80) {
    *used = 1;
    return lead;
  } else if (lead >= 0xc2 && lead <= 0xdf) {
    need = 1;
    cp = lead & 0x1f;
  } else if (lead >= 0xe0 && lead <= 0xef) {
    need = 2;
    cp = lead & 0x0f;
  } else if (lead >= 0xf0 && lead <= 0xf4) {
    need = 3;
    cp = lead & 0x07;
  } else {
    *used = 1;
    return 0xfffd;
  }
  // Fewer bytes left than the lead promises.
  if (length - at <= need) {
    *used = 1;
    return 0xfffd;
  }
  for (size_t i = 1; i <= need; ++i) {
    const unsigned char next = static_cast<unsigned char>(text[at + i]);
    if ((next & 0xc0) != 0x80) {
      *used = 1;
      return 0xfffd;
    }
    cp = (cp << 6) | (next & 0x3f);
  }
  // Overlong forms, surrogates and past U+10FFFF are not UTF-8.
  if ((need == 2 && cp < 0x800) || (need == 3 && (cp < 0x10000 || cp > 0x10ffff)) || (cp >= 0xd800 && cp <= 0xdfff)) {
    *used = 1;
    return 0xfffd;
  }
  *used = need + 1;
  return cp;
}

// How many UTF-16 chars the first `bytes` bytes of text (of `length` bytes) make. A negative offset is 0; one past
// the end is the end.
inline size_t Utf16Offset(const char* text, size_t length, int bytes) {
  if (bytes <= 0) return 0;
  const size_t limit = static_cast<size_t>(bytes) < length ? static_cast<size_t>(bytes) : length;
  size_t chars = 0;
  size_t at = 0;
  while (at < limit) {
    size_t used = 1;
    const char32_t cp = DecodeUtf8(text, length, at, &used);
    chars += cp >= 0x10000 ? 2 : 1;
    at += used;
  }
  return chars;
}

inline void Utf8ToUtf16(const char* text, size_t length, std::vector<uint16_t>* out) {
  out->clear();
  out->reserve(length);
  size_t at = 0;
  while (at < length) {
    size_t used = 1;
    const char32_t cp = DecodeUtf8(text, length, at, &used);
    if (cp >= 0x10000) {
      const char32_t v = cp - 0x10000;
      out->push_back(static_cast<uint16_t>(0xd800 + (v >> 10)));
      out->push_back(static_cast<uint16_t>(0xdc00 + (v & 0x3ff)));
    } else {
      out->push_back(static_cast<uint16_t>(cp));
    }
    at += used;
  }
}

// UTF-16 to UTF-8; an unpaired surrogate becomes U+FFFD.
inline void Utf16ToUtf8(const uint16_t* text, size_t length, std::string* out) {
  out->clear();
  out->reserve(length);
  for (size_t i = 0; i < length; ++i) {
    char32_t cp = text[i];
    if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < length && text[i + 1] >= 0xdc00 && text[i + 1] <= 0xdfff) {
      cp = 0x10000 + ((cp - 0xd800) << 10) + (text[i + 1] - 0xdc00);
      ++i;
    } else if (cp >= 0xd800 && cp <= 0xdfff) {
      cp = 0xfffd;
    }
    if (cp < 0x80) {
      out->push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
      out->push_back(static_cast<char>(0xc0 | (cp >> 6)));
      out->push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else if (cp < 0x10000) {
      out->push_back(static_cast<char>(0xe0 | (cp >> 12)));
      out->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
      out->push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else {
      out->push_back(static_cast<char>(0xf0 | (cp >> 18)));
      out->push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
      out->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
      out->push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    }
  }
}

}  // namespace droidtop

#endif  // DROIDTOP_RIME_UTF_H_
