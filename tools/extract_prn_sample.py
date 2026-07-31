#!/usr/bin/env python3
"""
extract_prn_sample.py

Usage: run this script from the repository root. It will read capture.prn, search for the
first occurrence of the XML StartJob control frame ("<?xml") and extract that XML plus the
following N bytes of binary data into samples/prn_examples/canon_chmp_sample.bin.

This lets you produce a small canonical CHMP sample from the uploaded capture for offline
replay/testing without committing large binary blobs into the repo.

The script writes two files:
 - samples/prn_examples/canon_chmp_sample.bin  (binary sample)
 - samples/prn_examples/canon_chmp_sample.meta.txt (info about offsets and sizes)

Adjust EXTRACT_AFTER and SAMPLE_SIZE to control how many bytes of raster payload are kept.
"""
import os
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(__file__))
INPUT = os.path.join(REPO_ROOT, 'capture.prn')
OUT_DIR = os.path.join(REPO_ROOT, 'samples', 'prn_examples')
OUT_BIN = os.path.join(OUT_DIR, 'canon_chmp_sample.bin')
OUT_META = os.path.join(OUT_DIR, 'canon_chmp_sample.meta.txt')

SAMPLE_SIZE = 64 * 1024  # bytes to extract after the XML header (adjust as needed)
MAX_SEARCH = 256 * 1024  # only scan this much when searching for XML

if not os.path.exists(INPUT):
    print('capture.prn not found in repo root; please place it there and re-run.')
    sys.exit(1)

os.makedirs(OUT_DIR, exist_ok=True)

with open(INPUT, 'rb') as f:
    data = f.read(MAX_SEARCH)

xml_pos = data.find(b'<?xml')
if xml_pos == -1:
    print('Could not find XML start in the first %d bytes; expanding search...' % MAX_SEARCH)
    with open(INPUT, 'rb') as f:
        data = f.read()
    xml_pos = data.find(b'<?xml')
    if xml_pos == -1:
        print('No XML start found in capture.prn — aborting.')
        sys.exit(1)

print('Found XML at offset', xml_pos)

# Read SAMPLE_SIZE bytes starting at xml_pos. If that overruns the file, clamp.
with open(INPUT, 'rb') as f:
    f.seek(xml_pos)
    sample = f.read(SAMPLE_SIZE)

with open(OUT_BIN, 'wb') as out:
    out.write(sample)

with open(OUT_META, 'w', encoding='utf-8') as m:
    m.write(f'input_file: {INPUT}\n')
    m.write(f'xml_offset: {xml_pos}\n')
    m.write(f'sample_size_requested: {SAMPLE_SIZE}\n')
    m.write(f'actual_sample_written: {len(sample)}\n')

print('Wrote sample to', OUT_BIN)
print('Wrote metadata to', OUT_META)
