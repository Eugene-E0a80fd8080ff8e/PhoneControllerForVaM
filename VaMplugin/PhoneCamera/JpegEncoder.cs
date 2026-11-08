using System;

namespace FivelSystems.LiveStreamConnectorToOBS
{
    /// <summary>
    /// Baseline sequential JPEG encoder with 4:2:0 chroma subsampling. Exists because
    /// <c>Texture2D.EncodeToJPG</c> must run on the main thread and this need not.
    /// Must not touch a Unity API. Reuses scratch state, so one instance per thread.
    /// </summary>
    public class JpegEncoder
    {
        // Annex K quantisation tables, natural (row-major) order.
        private static readonly byte[] BASE_QUANT_LUMA =
        {
            16, 11, 10, 16, 24, 40, 51, 61,
            12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77,
            24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99
        };

        private static readonly byte[] BASE_QUANT_CHROMA =
        {
            17, 18, 24, 47, 99, 99, 99, 99,
            18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99,
            47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99
        };

        /// <summary>Natural index to its position in the zig-zag sequence.</summary>
        private static readonly byte[] ZIGZAG =
        {
            0, 1, 5, 6, 14, 15, 27, 28,
            2, 4, 7, 13, 16, 26, 29, 42,
            3, 8, 12, 17, 25, 30, 41, 43,
            9, 11, 18, 24, 31, 40, 44, 53,
            10, 19, 23, 32, 39, 45, 52, 54,
            20, 22, 33, 38, 46, 51, 55, 60,
            21, 34, 37, 47, 50, 56, 59, 61,
            35, 36, 48, 49, 57, 58, 62, 63
        };

        /// <summary>Folded into the quantisation table so the DCT needs no descaling pass.</summary>
        private static readonly float[] AAN_SCALE =
        {
            1.0f, 1.387039845f, 1.306562965f, 1.175875602f,
            1.0f, 0.785694958f, 0.541196100f, 0.275899379f
        };

        // Annex K specifications: BITS[1..16] counts, then the symbol values.
        private static readonly byte[] BITS_DC_LUMA = { 0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0 };
        private static readonly byte[] VALS_DC_LUMA = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 };

        private static readonly byte[] BITS_DC_CHROMA = { 0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0 };
        private static readonly byte[] VALS_DC_CHROMA = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 };

        private static readonly byte[] BITS_AC_LUMA = { 0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D };
        private static readonly byte[] VALS_AC_LUMA =
        {
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12,
            0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08,
            0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16,
            0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
            0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
            0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79,
            0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98,
            0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
            0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6,
            0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
            0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4,
            0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
            0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA,
            0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
            0xF9, 0xFA
        };

        private static readonly byte[] BITS_AC_CHROMA = { 0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77 };
        private static readonly byte[] VALS_AC_CHROMA =
        {
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21,
            0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
            0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0,
            0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34,
            0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
            0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38,
            0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
            0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78,
            0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96,
            0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
            0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4,
            0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
            0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2,
            0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
            0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9,
            0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
            0xF9, 0xFA
        };

        private static readonly ushort[] s_dcLumaCode = new ushort[256];
        private static readonly byte[] s_dcLumaLen = new byte[256];
        private static readonly ushort[] s_acLumaCode = new ushort[256];
        private static readonly byte[] s_acLumaLen = new byte[256];
        private static readonly ushort[] s_dcChromaCode = new ushort[256];
        private static readonly byte[] s_dcChromaLen = new byte[256];
        private static readonly ushort[] s_acChromaCode = new ushort[256];
        private static readonly byte[] s_acChromaLen = new byte[256];

        static JpegEncoder()
        {
            BuildHuffmanTable(BITS_DC_LUMA, VALS_DC_LUMA, s_dcLumaCode, s_dcLumaLen);
            BuildHuffmanTable(BITS_AC_LUMA, VALS_AC_LUMA, s_acLumaCode, s_acLumaLen);
            BuildHuffmanTable(BITS_DC_CHROMA, VALS_DC_CHROMA, s_dcChromaCode, s_dcChromaLen);
            BuildHuffmanTable(BITS_AC_CHROMA, VALS_AC_CHROMA, s_acChromaCode, s_acChromaLen);
        }

        // Zig-zag order, as written to DQT.
        private readonly byte[] _quantLuma = new byte[64];
        private readonly byte[] _quantChroma = new byte[64];

        // Reciprocal quantisation folded with the AAN scale, natural order.
        private readonly float[] _scaleLuma = new float[64];
        private readonly float[] _scaleChroma = new float[64];

        private readonly float[] _block = new float[64];
        private readonly int[] _coefficients = new int[64];

        private byte[] _planeY;
        private byte[] _planeCb;
        private byte[] _planeCr;
        private int _planeWidth;
        private int _planeHeight;
        private int _chromaWidth;
        private int _chromaHeight;

        private byte[] _output = new byte[64 * 1024];
        private int _outputLength;
        private int _bitBuffer;
        private int _bitCount;

        private int _quality = -1;

        /// <summary>Runs longer than the frame; pair it with the length Encode returned.</summary>
        public byte[] OutputBuffer { get { return _output; } }

        /// <summary>
        /// Encodes tightly packed RGBA into <see cref="OutputBuffer"/>, returning its
        /// byte count or 0. Set <paramref name="flipVertical"/> for Unity readback data,
        /// whose first row is the bottom of the image.
        /// </summary>
        public int Encode(byte[] rgba, int width, int height, int quality, bool flipVertical, byte[] gammaLut)
        {
            if (rgba == null || width <= 0 || height <= 0) return 0;
            long needed = (long)width * height * 4;
            if (rgba.Length < needed) return 0;

            SetQuality(quality);
            BuildPlanes(rgba, width, height, flipVertical,
                        gammaLut != null && gammaLut.Length >= 256 ? gammaLut : null);

            _outputLength = 0;
            _bitBuffer = 0;
            _bitCount = 0;

            WriteHeaders(width, height);
            WriteScan(width, height);

            // Pad the final partial byte with ones, per the spec.
            WriteBits(0x7F, 7);
            Emit(0xFF);
            Emit(0xD9);

            return _outputLength;
        }

        private void WriteScan(int width, int height)
        {
            int dcY = 0;
            int dcCb = 0;
            int dcCr = 0;

            // 4:2:0 puts four luma blocks and one of each chroma block in a 16x16 MCU.
            for (int my = 0; my < height; my += 16)
            {
                for (int mx = 0; mx < width; mx += 16)
                {
                    dcY = EncodeBlock(_planeY, _planeWidth, _planeHeight, mx, my,
                                      _scaleLuma, s_dcLumaCode, s_dcLumaLen, s_acLumaCode, s_acLumaLen, dcY);
                    dcY = EncodeBlock(_planeY, _planeWidth, _planeHeight, mx + 8, my,
                                      _scaleLuma, s_dcLumaCode, s_dcLumaLen, s_acLumaCode, s_acLumaLen, dcY);
                    dcY = EncodeBlock(_planeY, _planeWidth, _planeHeight, mx, my + 8,
                                      _scaleLuma, s_dcLumaCode, s_dcLumaLen, s_acLumaCode, s_acLumaLen, dcY);
                    dcY = EncodeBlock(_planeY, _planeWidth, _planeHeight, mx + 8, my + 8,
                                      _scaleLuma, s_dcLumaCode, s_dcLumaLen, s_acLumaCode, s_acLumaLen, dcY);

                    dcCb = EncodeBlock(_planeCb, _chromaWidth, _chromaHeight, mx >> 1, my >> 1,
                                       _scaleChroma, s_dcChromaCode, s_dcChromaLen, s_acChromaCode, s_acChromaLen, dcCb);
                    dcCr = EncodeBlock(_planeCr, _chromaWidth, _chromaHeight, mx >> 1, my >> 1,
                                       _scaleChroma, s_dcChromaCode, s_dcChromaLen, s_acChromaCode, s_acChromaLen, dcCr);
                }
            }
        }

        /// <summary>
        /// Splits RGBA into a full-resolution luma plane and half-resolution chroma
        /// planes, two rows at a time so no accumulator buffer is needed.
        /// </summary>
        private void BuildPlanes(byte[] rgba, int width, int height, bool flipVertical, byte[] lut)
        {
            int chromaWidth = (width + 1) >> 1;
            int chromaHeight = (height + 1) >> 1;
            EnsurePlanes(width, height, chromaWidth, chromaHeight);

            int stride = width * 4;
            for (int y = 0; y < height; y += 2)
            {
                int yLower = y + 1 < height ? y + 1 : y;
                int rowTop = (flipVertical ? height - 1 - y : y) * stride;
                int rowBottom = (flipVertical ? height - 1 - yLower : yLower) * stride;
                int lumaTop = y * width;
                int lumaBottom = yLower * width;
                int chromaRow = (y >> 1) * chromaWidth;

                for (int x = 0; x < width; x += 2)
                {
                    int xRight = x + 1 < width ? x + 1 : x;

                    int a = rowTop + x * 4;
                    int b = rowTop + xRight * 4;
                    int c = rowBottom + x * 4;
                    int d = rowBottom + xRight * 4;

                    int ra, ga, ba, rb, gb, bb, rc, gc, bc, rd, gd, bd;
                    if (lut == null)
                    {
                        ra = rgba[a]; ga = rgba[a + 1]; ba = rgba[a + 2];
                        rb = rgba[b]; gb = rgba[b + 1]; bb = rgba[b + 2];
                        rc = rgba[c]; gc = rgba[c + 1]; bc = rgba[c + 2];
                        rd = rgba[d]; gd = rgba[d + 1]; bd = rgba[d + 2];
                    }
                    else
                    {
                        ra = lut[rgba[a]]; ga = lut[rgba[a + 1]]; ba = lut[rgba[a + 2]];
                        rb = lut[rgba[b]]; gb = lut[rgba[b + 1]]; bb = lut[rgba[b + 2]];
                        rc = lut[rgba[c]]; gc = lut[rgba[c + 1]]; bc = lut[rgba[c + 2]];
                        rd = lut[rgba[d]]; gd = lut[rgba[d + 1]]; bd = lut[rgba[d + 2]];
                    }

                    _planeY[lumaTop + x] = Luma(ra, ga, ba);
                    _planeY[lumaTop + xRight] = Luma(rb, gb, bb);
                    _planeY[lumaBottom + x] = Luma(rc, gc, bc);
                    _planeY[lumaBottom + xRight] = Luma(rd, gd, bd);

                    // Averaging RGB first is the same box filter at a quarter the conversions.
                    int r = (ra + rb + rc + rd) >> 2;
                    int g = (ga + gb + gc + gd) >> 2;
                    int bl = (ba + bb + bc + bd) >> 2;

                    int chromaIndex = chromaRow + (x >> 1);
                    _planeCb[chromaIndex] = ClampByte((-11056 * r - 21712 * g + 32768 * bl + 8421376) >> 16);
                    _planeCr[chromaIndex] = ClampByte((32768 * r - 27440 * g - 5328 * bl + 8421376) >> 16);
                }
            }
        }

        /// <summary>BT.601 luma in 16.16 fixed point; coefficients sum to one, so it cannot overflow.</summary>
        private static byte Luma(int r, int g, int b)
        {
            return (byte)((19595 * r + 38470 * g + 7471 * b + 32768) >> 16);
        }

        private static byte ClampByte(int v)
        {
            if (v < 0) return 0;
            if (v > 255) return 255;
            return (byte)v;
        }

        private void EnsurePlanes(int width, int height, int chromaWidth, int chromaHeight)
        {
            if (_planeY == null || _planeY.Length < width * height)
                _planeY = new byte[width * height];
            if (_planeCb == null || _planeCb.Length < chromaWidth * chromaHeight)
            {
                _planeCb = new byte[chromaWidth * chromaHeight];
                _planeCr = new byte[chromaWidth * chromaHeight];
            }
            _planeWidth = width;
            _planeHeight = height;
            _chromaWidth = chromaWidth;
            _chromaHeight = chromaHeight;
        }

        /// <summary>Returns the DC coefficient, which is the next block's predictor.</summary>
        private int EncodeBlock(byte[] plane, int planeWidth, int planeHeight, int x0, int y0,
                                float[] scale, ushort[] dcCode, byte[] dcLen, ushort[] acCode, byte[] acLen,
                                int previousDc)
        {
            float[] block = _block;

            if (x0 + 8 <= planeWidth && y0 + 8 <= planeHeight)
            {
                for (int y = 0; y < 8; y++)
                {
                    int row = (y0 + y) * planeWidth + x0;
                    int outRow = y * 8;
                    for (int x = 0; x < 8; x++) block[outRow + x] = plane[row + x] - 128f;
                }
            }
            else
            {
                // Edge blocks replicate the last row/column rather than reading past it.
                for (int y = 0; y < 8; y++)
                {
                    int sy = y0 + y;
                    if (sy >= planeHeight) sy = planeHeight - 1;
                    int row = sy * planeWidth;
                    int outRow = y * 8;
                    for (int x = 0; x < 8; x++)
                    {
                        int sx = x0 + x;
                        if (sx >= planeWidth) sx = planeWidth - 1;
                        block[outRow + x] = plane[row + sx] - 128f;
                    }
                }
            }

            for (int offset = 0; offset < 64; offset += 8) ForwardDct(block, offset, 1);
            for (int offset = 0; offset < 8; offset++) ForwardDct(block, offset, 8);

            int[] coefficients = _coefficients;
            for (int i = 0; i < 64; i++)
            {
                float v = block[i] * scale[i];
                coefficients[ZIGZAG[i]] = v < 0f ? (int)(v - 0.5f) : (int)(v + 0.5f);
            }

            int diff = coefficients[0] - previousDc;
            if (diff == 0)
            {
                WriteBits(dcCode[0], dcLen[0]);
            }
            else
            {
                int value;
                int length = CalcBits(diff, out value);
                WriteBits(dcCode[length], dcLen[length]);
                WriteBits(value, length);
            }

            int last = 63;
            while (last > 0 && coefficients[last] == 0) last--;
            if (last == 0)
            {
                WriteBits(acCode[0x00], acLen[0x00]);
                return coefficients[0];
            }

            for (int i = 1; i <= last; i++)
            {
                int runStart = i;
                while (coefficients[i] == 0 && i <= last) i++;
                int zeroes = i - runStart;
                while (zeroes >= 16)
                {
                    WriteBits(acCode[0xF0], acLen[0xF0]);
                    zeroes -= 16;
                }
                int value;
                int length = CalcBits(coefficients[i], out value);
                int symbol = (zeroes << 4) + length;
                WriteBits(acCode[symbol], acLen[symbol]);
                WriteBits(value, length);
            }
            if (last != 63) WriteBits(acCode[0x00], acLen[0x00]);

            return coefficients[0];
        }

        /// <summary>AAN forward DCT over eight samples; output stays scaled, see AAN_SCALE.</summary>
        private static void ForwardDct(float[] d, int offset, int step)
        {
            int i0 = offset;
            int i1 = i0 + step;
            int i2 = i1 + step;
            int i3 = i2 + step;
            int i4 = i3 + step;
            int i5 = i4 + step;
            int i6 = i5 + step;
            int i7 = i6 + step;

            float tmp0 = d[i0] + d[i7];
            float tmp7 = d[i0] - d[i7];
            float tmp1 = d[i1] + d[i6];
            float tmp6 = d[i1] - d[i6];
            float tmp2 = d[i2] + d[i5];
            float tmp5 = d[i2] - d[i5];
            float tmp3 = d[i3] + d[i4];
            float tmp4 = d[i3] - d[i4];

            float tmp10 = tmp0 + tmp3;
            float tmp13 = tmp0 - tmp3;
            float tmp11 = tmp1 + tmp2;
            float tmp12 = tmp1 - tmp2;

            d[i0] = tmp10 + tmp11;
            d[i4] = tmp10 - tmp11;

            float z1 = (tmp12 + tmp13) * 0.707106781f;
            d[i2] = tmp13 + z1;
            d[i6] = tmp13 - z1;

            tmp10 = tmp4 + tmp5;
            tmp11 = tmp5 + tmp6;
            tmp12 = tmp6 + tmp7;

            float z5 = (tmp10 - tmp12) * 0.382683433f;
            float z2 = tmp10 * 0.541196100f + z5;
            float z4 = tmp12 * 1.306562965f + z5;
            float z3 = tmp11 * 0.707106781f;

            float z11 = tmp7 + z3;
            float z13 = tmp7 - z3;

            d[i5] = z13 + z2;
            d[i3] = z13 - z2;
            d[i1] = z11 + z4;
            d[i7] = z11 - z4;
        }

        /// <summary>Magnitude category of a coefficient, and its bit pattern.</summary>
        private static int CalcBits(int value, out int bits)
        {
            int magnitude = value < 0 ? -value : value;
            if (value < 0) value--;
            int length = 1;
            while ((magnitude >>= 1) != 0) length++;
            bits = value & ((1 << length) - 1);
            return length;
        }

        private void SetQuality(int quality)
        {
            if (quality < 1) quality = 1;
            else if (quality > 100) quality = 100;
            if (quality == _quality) return;
            _quality = quality;

            int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
            for (int i = 0; i < 64; i++)
            {
                _quantLuma[ZIGZAG[i]] = ClampQuant((BASE_QUANT_LUMA[i] * scale + 50) / 100);
                _quantChroma[ZIGZAG[i]] = ClampQuant((BASE_QUANT_CHROMA[i] * scale + 50) / 100);
            }

            for (int row = 0; row < 8; row++)
            {
                for (int col = 0; col < 8; col++)
                {
                    int k = row * 8 + col;
                    float aan = AAN_SCALE[row] * AAN_SCALE[col] * 8f;
                    _scaleLuma[k] = 1f / (_quantLuma[ZIGZAG[k]] * aan);
                    _scaleChroma[k] = 1f / (_quantChroma[ZIGZAG[k]] * aan);
                }
            }
        }

        private static byte ClampQuant(int v)
        {
            if (v < 1) return 1;
            if (v > 255) return 255;
            return (byte)v;
        }

        /// <summary>Assigns canonical Huffman codes from a BITS/HUFFVAL specification.</summary>
        private static void BuildHuffmanTable(byte[] bits, byte[] values, ushort[] codes, byte[] lengths)
        {
            int code = 0;
            int k = 0;
            for (int length = 1; length <= 16; length++)
            {
                for (int i = 0; i < bits[length]; i++)
                {
                    byte symbol = values[k++];
                    codes[symbol] = (ushort)code;
                    lengths[symbol] = (byte)length;
                    code++;
                }
                code <<= 1;
            }
        }

        private void WriteHeaders(int width, int height)
        {
            Emit(0xFF); Emit(0xD8);
            Emit(0xFF); Emit(0xE0); EmitShort(16);
            Emit(0x4A); Emit(0x46); Emit(0x49); Emit(0x46); Emit(0x00);
            Emit(1); Emit(1); Emit(0);
            EmitShort(1); EmitShort(1);
            Emit(0); Emit(0);

            Emit(0xFF); Emit(0xDB); EmitShort(2 + 2 * 65);
            Emit(0x00); EmitBytes(_quantLuma);
            Emit(0x01); EmitBytes(_quantChroma);

            Emit(0xFF); Emit(0xC0); EmitShort(17);
            Emit(8);
            EmitShort(height); EmitShort(width);
            Emit(3);
            Emit(1); Emit(0x22); Emit(0);  // luma, sampled 2x2 against the chroma planes
            Emit(2); Emit(0x11); Emit(1);
            Emit(3); Emit(0x11); Emit(1);

            int dhtLength = 2 + 4 * 17
                            + VALS_DC_LUMA.Length + VALS_AC_LUMA.Length
                            + VALS_DC_CHROMA.Length + VALS_AC_CHROMA.Length;
            Emit(0xFF); Emit(0xC4); EmitShort(dhtLength);
            EmitHuffmanSpec(0x00, BITS_DC_LUMA, VALS_DC_LUMA);
            EmitHuffmanSpec(0x10, BITS_AC_LUMA, VALS_AC_LUMA);
            EmitHuffmanSpec(0x01, BITS_DC_CHROMA, VALS_DC_CHROMA);
            EmitHuffmanSpec(0x11, BITS_AC_CHROMA, VALS_AC_CHROMA);

            Emit(0xFF); Emit(0xDA); EmitShort(12);
            Emit(3);
            Emit(1); Emit(0x00);
            Emit(2); Emit(0x11);
            Emit(3); Emit(0x11);
            Emit(0); Emit(63); Emit(0);
        }

        private void EmitHuffmanSpec(byte id, byte[] bits, byte[] values)
        {
            Emit(id);
            for (int i = 1; i <= 16; i++) Emit(bits[i]);
            EmitBytes(values);
        }

        private void WriteBits(int code, int length)
        {
            _bitCount += length;
            _bitBuffer |= code << (24 - _bitCount);
            while (_bitCount >= 8)
            {
                byte b = (byte)((_bitBuffer >> 16) & 0xFF);
                Emit(b);
                // 0xFF starts a marker, so entropy-coded data escapes it.
                if (b == 0xFF) Emit(0x00);
                _bitBuffer <<= 8;
                _bitCount -= 8;
            }
        }

        private void Emit(byte b)
        {
            if (_outputLength == _output.Length)
            {
                byte[] grown = new byte[_output.Length * 2];
                Buffer.BlockCopy(_output, 0, grown, 0, _outputLength);
                _output = grown;
            }
            _output[_outputLength++] = b;
        }

        private void EmitShort(int v)
        {
            Emit((byte)((v >> 8) & 0xFF));
            Emit((byte)(v & 0xFF));
        }

        private void EmitBytes(byte[] bytes)
        {
            for (int i = 0; i < bytes.Length; i++) Emit(bytes[i]);
        }
    }
}
