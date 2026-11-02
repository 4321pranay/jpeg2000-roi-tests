# jpeg2000-roi-tests

Tests for `jpeg2000-roi`, kept separate so the library has no test dependencies.

The library is not published to a Maven repository yet, so install it locally first:

```
git clone https://github.com/4321pranay/jpeg2000-roi.git
(cd jpeg2000-roi && mvn -q install)
git clone https://github.com/4321pranay/jpeg2000-roi-tests.git
cd jpeg2000-roi-tests && mvn test
```

Tests that need `opj_decompress` (OpenJPEG) or `pdfimages` (Poppler) are skipped when those tools are
missing. Test classes share the library's package so they can reach package-private helpers.

| Class | Covers |
|---|---|
| `RoiJ2KImageWriteParamTest` | validation, defensive copies, settings |
| `RoiJ2KImageWriterSpiTest` | ImageIO registration, no shadowing of the standard writer |
| `RoiJ2KImageWriterTest` | byte-identical output without ROI (lossy 9/7, 5/3, lossless), RGN marker, JP2 / codestream, rate, temp-file cleanup on failure |
| `RoiQualityTest` | decoded with OpenJPEG: several ROIs, RGB, raw codestream, tiling, ROI start level, code-block alignment |
| `RoiSourceSettingsTest` | ROIs rejected with source region / subsampling; allowed without ROIs |
| `RoiDecodingTest` | OpenJPEG / Poppler decoding, ROI vs plain, known jai-imageio reader bug |
| `A4ScanScenarioTest` | A4 @ 300 DPI with a noisy 1.2" QR at a 300 KB cap |

## License
BSD 3-Clause, see `LICENSE`.
