using System.Runtime.InteropServices;

namespace GrootWidget;

internal readonly record struct RgbaColor(byte R, byte G, byte B, byte A = 255);

internal sealed unsafe class DirectWriteRenderer : IDisposable
{
    private static readonly Guid ClsidWicImagingFactory = new("cacaf262-9370-4615-a13b-9f5539da4c0a");
    private static readonly Guid IidWicImagingFactory = new("ec5ec8a9-c395-4314-9c77-54d7a935ff70");
    private static readonly Guid WicPixelFormat32bppPbgra = new("6fddc324-4e03-4bfe-b185-3d77768dc910");
    private static readonly Guid IidD2d1Factory = new("06152247-6f50-465a-9245-118bfd3b6007");
    private static readonly Guid IidDWriteFactory5 = new("958db99a-be2a-4f09-af7d-65189803d1d3");
    private static readonly Guid IidDWriteTextFormat1 = new("5f174b49-0d8b-4cfb-8bca-f1cce9d06c67");

    private const uint D2d1DrawTextOptionsClip = 0x2;
    private const uint D2d1DrawTextOptionsEnableColorFont = 0x4;

    private static readonly RgbaColor PanelColor = new(13, 17, 20);
    private static readonly RgbaColor LabelColor = new(170, 181, 187);
    private static readonly RgbaColor LampOffColor = new(8, 10, 12);
    private static readonly RgbaColor AcrylicTintColor = new(0, 0, 0, 102);

    private IntPtr wicFactory;
    private IntPtr d2dFactory;
    private IntPtr dwriteFactory;
    private IntPtr memoryFontLoader;
    private IntPtr emojiFontCollection;
    private IntPtr fontFallback;
    private GCHandle emojiFontPin;
    private bool loaderRegistered;

    private IntPtr wicBitmap;
    private IntPtr renderTarget;
    private int surfaceWidth;
    private int surfaceHeight;

    private IntPtr leftFormat;
    private IntPtr rightFormat;
    private float cachedFontSize;
    private bool disposed;

    internal DirectWriteRenderer()
    {
        try
        {
            CheckHr(CoCreateInstance(in ClsidWicImagingFactory, IntPtr.Zero, 1, in IidWicImagingFactory, out wicFactory), "CoCreateInstance(WIC)");
            CheckHr(D2D1CreateFactory(0, in IidD2d1Factory, IntPtr.Zero, out d2dFactory), "D2D1CreateFactory");
            CheckHr(DWriteCreateFactory(0, in IidDWriteFactory5, out dwriteFactory), "DWriteCreateFactory");
            fontFallback = BuildFontFallback();
        }
        catch
        {
            Dispose();
            throw;
        }
    }

    internal void Render(
        int width,
        int height,
        int dpi,
        WidgetItem[] items,
        WidgetSettings settings,
        int scrollOffset,
        bool connected,
        bool acrylic,
        IntPtr destination,
        int destinationStride)
    {
        if (width <= 0 || height <= 0) return;
        EnsureSurface(width, height);
        var scale = (int value) => Math.Max(1, (int)Math.Round(value * dpi / 96d));
        EnsureTextFormats(scale(settings.FontSize));

        RtBeginDraw(renderTarget);
        try
        {
            RtSetAntialiasMode(renderTarget, 0); // PerPrimitive
            RtSetTextAntialiasMode(renderTarget, 2); // Grayscale (required for alpha/Acrylic surfaces)
            var clear = acrylic ? new D2d1ColorF(0, 0, 0, 0) : ToColorF(PanelColor);
            RtClear(renderTarget, in clear);
            if (acrylic)
            {
                using var tintBrush = CreateBrush(AcrylicTintColor);
                var bounds = new D2d1RectF(0, 0, width, height);
                RtFillRectangle(renderTarget, in bounds, tintBrush.Ptr);
            }

            var rowHeight = scale(settings.RowHeight);
            var topPadding = scale(4);
            if (items.Length == 0)
            {
                DrawRow(width, height, topPadding - scrollOffset, rowHeight, scale, "WIDGET", connected ? "EMPTY" : "连接中…", Tone("yellow"));
            }
            for (var index = 0; index < items.Length; index++)
            {
                var item = items[index];
                var rowTop = topPadding + index * rowHeight - scrollOffset;
                if (rowTop + rowHeight < 0 || rowTop > height) continue;
                DrawRow(width, height, rowTop, rowHeight, scale, item.Label, item.Value ?? "", Tone(item.Color));
                if (string.IsNullOrEmpty(item.Value)) DrawLamps(width, rowTop, rowHeight, scale, item.Color);
            }

            if (!connected)
            {
                using var dotBrush = CreateBrush(Tone("yellow"));
                var diameter = scale(5);
                var radius = diameter / 2f;
                var ellipse = new D2d1Ellipse(width - scale(9) + radius, scale(3) + radius, radius, radius);
                RtFillEllipse(renderTarget, in ellipse, dotBrush.Ptr);
            }
        }
        finally
        {
            var hr = RtEndDraw(renderTarget, out _, out _);
            if (hr < 0)
            {
                ReleaseSurface();
                CheckHr(hr, "ID2D1RenderTarget.EndDraw");
            }
        }

        CheckHr(WicCopyPixels(wicBitmap, IntPtr.Zero, (uint)destinationStride, (uint)(destinationStride * height), destination), "IWICBitmap.CopyPixels");
    }

    internal byte[] RenderToBytes(
        int width,
        int height,
        int dpi,
        WidgetItem[] items,
        WidgetSettings settings,
        int scrollOffset = 0,
        bool connected = true,
        bool acrylic = false)
    {
        var stride = width * 4;
        var buffer = new byte[stride * height];
        fixed (byte* ptr = buffer)
            Render(width, height, dpi, items, settings, scrollOffset, connected, acrylic, (IntPtr)ptr, stride);
        return buffer;
    }

    private void DrawRow(int width, int height, int rowTop, int rowHeight, Func<int, int> scale, string label, string value, RgbaColor color)
    {
        if (rowTop + rowHeight < 0 || rowTop > height) return;
        var padding = scale(16);
        var gap = scale(16);
        var available = Math.Max(0, width - 2 * padding - gap);
        if (available <= 0) return;

        var measuredLabel = MeasureTextWidth(label, leftFormat, rowHeight);
        var labelWidth = Math.Min((int)Math.Ceiling(measuredLabel), available / 2);
        var rightWidth = Math.Max(0, available - labelWidth);

        if (labelWidth > 0 && label.Length > 0)
        {
            using var labelBrush = CreateBrush(LabelColor);
            DrawText(label, leftFormat, padding, rowTop, labelWidth, rowHeight, labelBrush.Ptr);
        }
        if (rightWidth > 0 && value.Length > 0)
        {
            using var valueBrush = CreateBrush(color);
            DrawText(value, rightFormat, padding + labelWidth + gap, rowTop, rightWidth, rowHeight, valueBrush.Ptr);
        }
    }

    private void DrawLamps(int width, int rowTop, int rowHeight, Func<int, int> scale, string activeColor)
    {
        var slot = scale(16);
        var gap = scale(8);
        var left = width - scale(16) - slot * 3 - gap * 2;
        var colors = new[] { "red", "yellow", "green" };
        for (var i = 0; i < colors.Length; i++)
        {
            var active = activeColor == colors[i];
            var size = active ? slot : scale(12);
            var radius = size / 2f;
            var cx = left + i * (slot + gap) + slot / 2f;
            var cy = rowTop + rowHeight / 2f;
            using var brush = CreateBrush(active ? Tone(colors[i]) : LampOffColor);
            var ellipse = new D2d1Ellipse(cx, cy, radius, radius);
            RtFillEllipse(renderTarget, in ellipse, brush.Ptr);
        }
    }

    private float MeasureTextWidth(string text, IntPtr format, float maxHeight)
    {
        if (text.Length == 0) return 0;
        var layout = CreateTextLayout(text, format, 100000f, maxHeight);
        try
        {
            CheckHr(DwTextLayoutGetMetrics(layout, out var metrics), "IDWriteTextLayout.GetMetrics");
            return metrics.WidthIncludingTrailingWhitespace;
        }
        finally { Release(ref layout); }
    }

    private void DrawText(string text, IntPtr format, float x, float y, float width, float height, IntPtr brush)
    {
        var layout = CreateTextLayout(text, format, width, height);
        try
        {
            RtDrawTextLayout(renderTarget, new D2d1Point2F(x, y), layout, brush, D2d1DrawTextOptionsClip | D2d1DrawTextOptionsEnableColorFont);
        }
        finally { Release(ref layout); }
    }

    private IntPtr CreateTextLayout(string text, IntPtr format, float maxWidth, float maxHeight)
    {
        fixed (char* ptr = text)
        {
            CheckHr(DwCreateTextLayout(dwriteFactory, ptr, (uint)text.Length, format, maxWidth, maxHeight, out var layout), "IDWriteFactory.CreateTextLayout");
            return layout;
        }
    }

    private IntPtr BuildFontFallback()
    {
        using var stream = typeof(DirectWriteRenderer).Assembly.GetManifestResourceStream("GrootWidget.Twemoji");
        if (stream is null) return IntPtr.Zero;

        var fontBytes = new byte[stream.Length];
        var read = 0;
        while (read < fontBytes.Length)
        {
            var chunk = stream.Read(fontBytes, read, fontBytes.Length - read);
            if (chunk <= 0) break;
            read += chunk;
        }
        emojiFontPin = GCHandle.Alloc(fontBytes, GCHandleType.Pinned);

        CheckHr(DwCreateInMemoryFontFileLoader(dwriteFactory, out memoryFontLoader), "CreateInMemoryFontFileLoader");
        CheckHr(DwRegisterFontFileLoader(dwriteFactory, memoryFontLoader), "RegisterFontFileLoader");
        loaderRegistered = true;

        IntPtr fontFile = IntPtr.Zero;
        IntPtr fontSetBuilder = IntPtr.Zero;
        IntPtr fontSet = IntPtr.Zero;
        IntPtr systemFallback = IntPtr.Zero;
        IntPtr fallbackBuilder = IntPtr.Zero;
        try
        {
            CheckHr(DwCreateInMemoryFontFileReference(memoryFontLoader, dwriteFactory, emojiFontPin.AddrOfPinnedObject(), (uint)fontBytes.Length, IntPtr.Zero, out fontFile), "CreateInMemoryFontFileReference");
            CheckHr(DwCreateFontSetBuilder1(dwriteFactory, out fontSetBuilder), "CreateFontSetBuilder1");
            CheckHr(DwFontSetBuilderAddFontFile(fontSetBuilder, fontFile), "FontSetBuilder.AddFontFile");
            CheckHr(DwFontSetBuilderCreateFontSet(fontSetBuilder, out fontSet), "FontSetBuilder.CreateFontSet");
            CheckHr(DwCreateFontCollectionFromFontSet(dwriteFactory, fontSet, out emojiFontCollection), "CreateFontCollectionFromFontSet");

            CheckHr(DwGetSystemFontFallback(dwriteFactory, out systemFallback), "GetSystemFontFallback");
            CheckHr(DwCreateFontFallbackBuilder(dwriteFactory, out fallbackBuilder), "CreateFontFallbackBuilder");

            // Priority 1: Map flag regional indicators and subdivision tag flags to embedded Twemoji COLR font,
            // because Windows Segoe UI Emoji intentionally omits flag glyphs and renders two-letter codes instead.
            var flagRanges = stackalloc DWriteUnicodeRange[3];
            flagRanges[0] = new DWriteUnicodeRange(0x1F1E6, 0x1F1FF); // Regional Indicator Symbols (JP, US, CN, HK, etc.)
            flagRanges[1] = new DWriteUnicodeRange(0x1F3F4, 0x1F3F4); // Waving Black Flag (subdivision flag base)
            flagRanges[2] = new DWriteUnicodeRange(0xE0020, 0xE007F); // Tag characters (subdivision flags)
            AddFallbackMapping(fallbackBuilder, flagRanges, 3, "Twemoji Mozilla", emojiFontCollection);

            // Priority 2: Standard Windows system fallback (Cascadia Mono -> CJK -> Segoe UI Emoji -> Segoe UI Symbol).
            CheckHr(DwFallbackBuilderAddMappings(fallbackBuilder, systemFallback), "FontFallbackBuilder.AddMappings");

            // Priority 3: Embedded Twemoji as last-resort fallback for any remaining emoji missing from system fonts.
            var fallbackRange = new DWriteUnicodeRange(0x2000, 0x10FFFF);
            AddFallbackMapping(fallbackBuilder, &fallbackRange, 1, "Twemoji Mozilla", emojiFontCollection);

            CheckHr(DwFallbackBuilderCreateFontFallback(fallbackBuilder, out var built), "FontFallbackBuilder.CreateFontFallback");
            return built;
        }
        finally
        {
            Release(ref fallbackBuilder);
            Release(ref systemFallback);
            Release(ref fontSet);
            Release(ref fontSetBuilder);
            Release(ref fontFile);
        }
    }

    private static void AddFallbackMapping(IntPtr fallbackBuilder, DWriteUnicodeRange* ranges, uint rangeCount, string familyName, IntPtr collection)
    {
        fixed (char* familyPtr = familyName)
        {
            var families = stackalloc char*[1];
            families[0] = familyPtr;
            CheckHr(DwFallbackBuilderAddMapping(fallbackBuilder, ranges, rangeCount, families, 1, collection, null, null, 1.0f), "FontFallbackBuilder.AddMapping");
        }
    }

    private void EnsureSurface(int width, int height)
    {
        if (wicBitmap != IntPtr.Zero && renderTarget != IntPtr.Zero && surfaceWidth == width && surfaceHeight == height) return;
        ReleaseSurface();
        CheckHr(WicCreateBitmap(wicFactory, (uint)width, (uint)height, in WicPixelFormat32bppPbgra, 2, out wicBitmap), "IWICImagingFactory.CreateBitmap");
        var props = new D2d1RenderTargetProperties
        {
            Type = 0, // Default
            PixelFormat = new D2d1PixelFormat { Format = 87 /* DXGI_FORMAT_B8G8R8A8_UNORM */, AlphaMode = 1 /* Premultiplied */ },
            DpiX = 96f,
            DpiY = 96f,
            Usage = 0,
            MinLevel = 0
        };
        CheckHr(D2dCreateWicBitmapRenderTarget(d2dFactory, wicBitmap, in props, out renderTarget), "ID2D1Factory.CreateWicBitmapRenderTarget");
        surfaceWidth = width;
        surfaceHeight = height;
    }

    private void ReleaseSurface()
    {
        Release(ref renderTarget);
        Release(ref wicBitmap);
        surfaceWidth = 0;
        surfaceHeight = 0;
    }

    private void EnsureTextFormats(float fontSizePx)
    {
        if (leftFormat != IntPtr.Zero && rightFormat != IntPtr.Zero && Math.Abs(cachedFontSize - fontSizePx) < 0.01f) return;
        Release(ref leftFormat);
        Release(ref rightFormat);
        leftFormat = CreateTextFormat(fontSizePx, textAlignment: 0); // Leading
        rightFormat = CreateTextFormat(fontSizePx, textAlignment: 1); // Trailing
        cachedFontSize = fontSizePx;
    }

    private IntPtr CreateTextFormat(float fontSizePx, uint textAlignment)
    {
        IntPtr format = IntPtr.Zero;
        IntPtr ellipsisSign = IntPtr.Zero;
        IntPtr format1 = IntPtr.Zero;
        try
        {
            fixed (char* family = "Cascadia Mono")
            fixed (char* locale = "zh-CN")
            {
                CheckHr(DwCreateTextFormat(dwriteFactory, family, IntPtr.Zero, 400 /* Regular */, 0 /* Normal */, 5 /* Normal */, fontSizePx, locale, out format), "CreateTextFormat");
            }
            CheckHr(DwTextFormatSetTextAlignment(format, textAlignment), "SetTextAlignment");
            CheckHr(DwTextFormatSetParagraphAlignment(format, 2 /* Center */), "SetParagraphAlignment");
            CheckHr(DwTextFormatSetWordWrapping(format, 1 /* NoWrap */), "SetWordWrapping");
            CheckHr(DwCreateEllipsisTrimmingSign(dwriteFactory, format, out ellipsisSign), "CreateEllipsisTrimmingSign");
            var trimming = new DWriteTrimming { Granularity = 1 /* Character */, Delimiter = 0, DelimiterCount = 0 };
            CheckHr(DwTextFormatSetTrimming(format, in trimming, ellipsisSign), "SetTrimming");

            if (fontFallback != IntPtr.Zero && QueryInterface(format, in IidDWriteTextFormat1, out format1) == 0 && format1 != IntPtr.Zero)
            {
                CheckHr(DwTextFormat1SetFontFallback(format1, fontFallback), "SetFontFallback");
            }

            var result = format;
            format = IntPtr.Zero;
            return result;
        }
        finally
        {
            Release(ref format1);
            Release(ref ellipsisSign);
            Release(ref format);
        }
    }

    private BrushGuard CreateBrush(RgbaColor color)
    {
        var d2dColor = ToColorF(color);
        CheckHr(RtCreateSolidColorBrush(renderTarget, in d2dColor, IntPtr.Zero, out var brush), "CreateSolidColorBrush");
        return new BrushGuard(brush);
    }

    private static D2d1ColorF ToColorF(RgbaColor color) => new(color.R / 255f, color.G / 255f, color.B / 255f, color.A / 255f);

    private static RgbaColor Tone(string color) => color switch
    {
        "red" => new(255, 102, 92),
        "yellow" => new(232, 183, 93),
        _ => new(88, 214, 169)
    };

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        Release(ref leftFormat);
        Release(ref rightFormat);
        ReleaseSurface();
        Release(ref fontFallback);
        Release(ref emojiFontCollection);
        if (loaderRegistered && dwriteFactory != IntPtr.Zero && memoryFontLoader != IntPtr.Zero)
        {
            DwUnregisterFontFileLoader(dwriteFactory, memoryFontLoader);
            loaderRegistered = false;
        }
        Release(ref memoryFontLoader);
        Release(ref dwriteFactory);
        Release(ref d2dFactory);
        Release(ref wicFactory);
        if (emojiFontPin.IsAllocated) emojiFontPin.Free();
    }

    private readonly struct BrushGuard : IDisposable
    {
        internal readonly IntPtr Ptr;
        internal BrushGuard(IntPtr ptr) => Ptr = ptr;
        public void Dispose() { var p = Ptr; Release(ref p); }
    }

    private static void CheckHr(int hr, string operation)
    {
        if (hr < 0) Marshal.ThrowExceptionForHR(hr, new IntPtr(-1));
    }

    private static IntPtr* VTable(IntPtr com) => *(IntPtr**)com;

    private static int QueryInterface(IntPtr com, in Guid iid, out IntPtr result)
    {
        var fn = (delegate* unmanaged[Stdcall]<IntPtr, in Guid, out IntPtr, int>)VTable(com)[0];
        return fn(com, in iid, out result);
    }

    private static void Release(ref IntPtr com)
    {
        if (com == IntPtr.Zero) return;
        var ptr = com;
        com = IntPtr.Zero;
        var fn = (delegate* unmanaged[Stdcall]<IntPtr, uint>)VTable(ptr)[2];
        fn(ptr);
    }

    // IWICImagingFactory (slot 17 = CreateBitmap)
    private static int WicCreateBitmap(IntPtr factory, uint width, uint height, in Guid pixelFormat, uint option, out IntPtr bitmap)
        => ((delegate* unmanaged[Stdcall]<IntPtr, uint, uint, in Guid, uint, out IntPtr, int>)VTable(factory)[17])(factory, width, height, in pixelFormat, option, out bitmap);

    // IWICBitmapSource (slot 7 = CopyPixels)
    private static int WicCopyPixels(IntPtr bitmap, IntPtr rect, uint stride, uint bufferSize, IntPtr buffer)
        => ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr, uint, uint, IntPtr, int>)VTable(bitmap)[7])(bitmap, rect, stride, bufferSize, buffer);

    // ID2D1Factory (slot 13 = CreateWicBitmapRenderTarget)
    private static int D2dCreateWicBitmapRenderTarget(IntPtr factory, IntPtr wicBitmapTarget, in D2d1RenderTargetProperties props, out IntPtr rt)
        => ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr, in D2d1RenderTargetProperties, out IntPtr, int>)VTable(factory)[13])(factory, wicBitmapTarget, in props, out rt);

    // ID2D1RenderTarget (inherits IUnknown[0..2] + ID2D1Resource[3] => methods start at slot 4)
    private static int RtCreateSolidColorBrush(IntPtr rt, in D2d1ColorF color, IntPtr brushProps, out IntPtr brush)
        => ((delegate* unmanaged[Stdcall]<IntPtr, in D2d1ColorF, IntPtr, out IntPtr, int>)VTable(rt)[8])(rt, in color, brushProps, out brush);

    private static void RtFillRectangle(IntPtr rt, in D2d1RectF rect, IntPtr brush)
        => ((delegate* unmanaged[Stdcall]<IntPtr, in D2d1RectF, IntPtr, void>)VTable(rt)[17])(rt, in rect, brush);

    private static void RtFillEllipse(IntPtr rt, in D2d1Ellipse ellipse, IntPtr brush)
        => ((delegate* unmanaged[Stdcall]<IntPtr, in D2d1Ellipse, IntPtr, void>)VTable(rt)[21])(rt, in ellipse, brush);

    private static void RtDrawTextLayout(IntPtr rt, D2d1Point2F origin, IntPtr textLayout, IntPtr brush, uint options)
        => ((delegate* unmanaged[Stdcall]<IntPtr, D2d1Point2F, IntPtr, IntPtr, uint, void>)VTable(rt)[28])(rt, origin, textLayout, brush, options);

    private static void RtSetAntialiasMode(IntPtr rt, uint mode)
        => ((delegate* unmanaged[Stdcall]<IntPtr, uint, void>)VTable(rt)[32])(rt, mode);

    private static void RtSetTextAntialiasMode(IntPtr rt, uint mode)
        => ((delegate* unmanaged[Stdcall]<IntPtr, uint, void>)VTable(rt)[34])(rt, mode);

    private static void RtClear(IntPtr rt, in D2d1ColorF color)
        => ((delegate* unmanaged[Stdcall]<IntPtr, in D2d1ColorF, void>)VTable(rt)[47])(rt, in color);

    private static void RtBeginDraw(IntPtr rt)
        => ((delegate* unmanaged[Stdcall]<IntPtr, void>)VTable(rt)[48])(rt);

    private static int RtEndDraw(IntPtr rt, out ulong tag1, out ulong tag2)
        => ((delegate* unmanaged[Stdcall]<IntPtr, out ulong, out ulong, int>)VTable(rt)[49])(rt, out tag1, out tag2);

    // IDWriteFactory5 vtable slots
    private static int DwRegisterFontFileLoader(IntPtr factory, IntPtr loader)
        => ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr, int>)VTable(factory)[13])(factory, loader);

    private static int DwUnregisterFontFileLoader(IntPtr factory, IntPtr loader)
        => ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr, int>)VTable(factory)[14])(factory, loader);

    private static int DwCreateTextFormat(IntPtr factory, char* familyName, IntPtr collection, uint weight, uint style, uint stretch, float fontSize, char* localeName, out IntPtr textFormat)
        => ((delegate* unmanaged[Stdcall]<IntPtr, char*, IntPtr, uint, uint, uint, float, char*, out IntPtr, int>)VTable(factory)[15])(factory, familyName, collection, weight, style, stretch, fontSize, localeName, out textFormat);

    private static int DwCreateTextLayout(IntPtr factory, char* text, uint length, IntPtr format, float maxWidth, float maxHeight, out IntPtr textLayout)
        => ((delegate* unmanaged[Stdcall]<IntPtr, char*, uint, IntPtr, float, float, out IntPtr, int>)VTable(factory)[18])(factory, text, length, format, maxWidth, maxHeight, out textLayout);

    private static int DwCreateEllipsisTrimmingSign(IntPtr factory, IntPtr format, out IntPtr trimmingSign)
        => ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr, out IntPtr, int>)VTable(factory)[20])(factory, format, out trimmingSign);

    private static int DwGetSystemFontFallback(IntPtr factory, out IntPtr fallback)
        => ((delegate* unmanaged[Stdcall]<IntPtr, out IntPtr, int>)VTable(factory)[26])(factory, out fallback);

    private static int DwCreateFontFallbackBuilder(IntPtr factory, out IntPtr builder)
        => ((delegate* unmanaged[Stdcall]<IntPtr, out IntPtr, int>)VTable(factory)[27])(factory, out builder);

    private static int DwCreateFontCollectionFromFontSet(IntPtr factory, IntPtr fontSet, out IntPtr collection)
        => ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr, out IntPtr, int>)VTable(factory)[37])(factory, fontSet, out collection);

    private static int DwCreateFontSetBuilder1(IntPtr factory, out IntPtr builder)
        => ((delegate* unmanaged[Stdcall]<IntPtr, out IntPtr, int>)VTable(factory)[43])(factory, out builder);

    private static int DwCreateInMemoryFontFileLoader(IntPtr factory, out IntPtr loader)
        => ((delegate* unmanaged[Stdcall]<IntPtr, out IntPtr, int>)VTable(factory)[44])(factory, out loader);

    // IDWriteInMemoryFontFileLoader (slot 4 = CreateInMemoryFontFileReference)
    private static int DwCreateInMemoryFontFileReference(IntPtr loader, IntPtr factory, IntPtr fontData, uint fontDataSize, IntPtr ownerObject, out IntPtr fontFile)
        => ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr, IntPtr, uint, IntPtr, out IntPtr, int>)VTable(loader)[4])(loader, factory, fontData, fontDataSize, ownerObject, out fontFile);

    // IDWriteFontSetBuilder1 (slot 6 = CreateFontSet, slot 7 = AddFontFile)
    private static int DwFontSetBuilderCreateFontSet(IntPtr builder, out IntPtr fontSet)
        => ((delegate* unmanaged[Stdcall]<IntPtr, out IntPtr, int>)VTable(builder)[6])(builder, out fontSet);

    private static int DwFontSetBuilderAddFontFile(IntPtr builder, IntPtr fontFile)
        => ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr, int>)VTable(builder)[7])(builder, fontFile);

    // IDWriteFontFallbackBuilder (slot 3 = AddMapping, slot 4 = AddMappings, slot 5 = CreateFontFallback)
    private static int DwFallbackBuilderAddMapping(IntPtr builder, DWriteUnicodeRange* ranges, uint rangeCount, char** targetFamilyNames, uint targetFamilyNamesCount, IntPtr fontCollection, char* localeName, char* baseFamilyName, float scale)
        => ((delegate* unmanaged[Stdcall]<IntPtr, DWriteUnicodeRange*, uint, char**, uint, IntPtr, char*, char*, float, int>)VTable(builder)[3])(builder, ranges, rangeCount, targetFamilyNames, targetFamilyNamesCount, fontCollection, localeName, baseFamilyName, scale);

    private static int DwFallbackBuilderAddMappings(IntPtr builder, IntPtr fallback)
        => ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr, int>)VTable(builder)[4])(builder, fallback);

    private static int DwFallbackBuilderCreateFontFallback(IntPtr builder, out IntPtr fallback)
        => ((delegate* unmanaged[Stdcall]<IntPtr, out IntPtr, int>)VTable(builder)[5])(builder, out fallback);

    // IDWriteTextFormat / IDWriteTextFormat1
    private static int DwTextFormatSetTextAlignment(IntPtr format, uint alignment)
        => ((delegate* unmanaged[Stdcall]<IntPtr, uint, int>)VTable(format)[3])(format, alignment);

    private static int DwTextFormatSetParagraphAlignment(IntPtr format, uint alignment)
        => ((delegate* unmanaged[Stdcall]<IntPtr, uint, int>)VTable(format)[4])(format, alignment);

    private static int DwTextFormatSetWordWrapping(IntPtr format, uint wrapping)
        => ((delegate* unmanaged[Stdcall]<IntPtr, uint, int>)VTable(format)[5])(format, wrapping);

    private static int DwTextFormatSetTrimming(IntPtr format, in DWriteTrimming trimming, IntPtr trimmingSign)
        => ((delegate* unmanaged[Stdcall]<IntPtr, in DWriteTrimming, IntPtr, int>)VTable(format)[9])(format, in trimming, trimmingSign);

    private static int DwTextFormat1SetFontFallback(IntPtr format1, IntPtr fallback)
        => ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr, int>)VTable(format1)[34])(format1, fallback);

    // IDWriteTextLayout (slot 60 = GetMetrics)
    private static int DwTextLayoutGetMetrics(IntPtr layout, out DWriteTextMetrics metrics)
        => ((delegate* unmanaged[Stdcall]<IntPtr, out DWriteTextMetrics, int>)VTable(layout)[60])(layout, out metrics);

    [DllImport("ole32.dll")]
    private static extern int CoCreateInstance(in Guid rclsid, IntPtr pUnkOuter, uint dwClsContext, in Guid riid, out IntPtr ppv);

    [DllImport("d2d1.dll")]
    private static extern int D2D1CreateFactory(uint factoryType, in Guid riid, IntPtr pFactoryOptions, out IntPtr ppIFactory);

    [DllImport("dwrite.dll")]
    private static extern int DWriteCreateFactory(uint factoryType, in Guid iid, out IntPtr factory);

    [StructLayout(LayoutKind.Sequential)]
    private struct D2d1PixelFormat { public uint Format; public uint AlphaMode; }

    [StructLayout(LayoutKind.Sequential)]
    private struct D2d1RenderTargetProperties
    {
        public uint Type;
        public D2d1PixelFormat PixelFormat;
        public float DpiX;
        public float DpiY;
        public uint Usage;
        public uint MinLevel;
    }

    [StructLayout(LayoutKind.Sequential)]
    private readonly struct D2d1ColorF
    {
        public readonly float R, G, B, A;
        public D2d1ColorF(float r, float g, float b, float a) { R = r; G = g; B = b; A = a; }
    }

    [StructLayout(LayoutKind.Sequential)]
    private readonly struct D2d1Point2F
    {
        public readonly float X, Y;
        public D2d1Point2F(float x, float y) { X = x; Y = y; }
    }

    [StructLayout(LayoutKind.Sequential)]
    private readonly struct D2d1RectF
    {
        public readonly float Left, Top, Right, Bottom;
        public D2d1RectF(float left, float top, float right, float bottom) { Left = left; Top = top; Right = right; Bottom = bottom; }
    }

    [StructLayout(LayoutKind.Sequential)]
    private readonly struct D2d1Ellipse
    {
        public readonly D2d1Point2F Point;
        public readonly float RadiusX, RadiusY;
        public D2d1Ellipse(float x, float y, float rx, float ry) { Point = new D2d1Point2F(x, y); RadiusX = rx; RadiusY = ry; }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DWriteTrimming { public uint Granularity; public uint Delimiter; public uint DelimiterCount; }

    [StructLayout(LayoutKind.Sequential)]
    private readonly struct DWriteUnicodeRange
    {
        public readonly uint First, Last;
        public DWriteUnicodeRange(uint first, uint last) { First = first; Last = last; }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DWriteTextMetrics
    {
        public float Left;
        public float Top;
        public float Width;
        public float WidthIncludingTrailingWhitespace;
        public float Height;
        public float LayoutWidth;
        public float LayoutHeight;
        public uint MaxBidiReorderingDepth;
        public uint LineCount;
    }
}
