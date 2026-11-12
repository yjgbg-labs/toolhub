package groot.wechat

import com.google.zxing.{BarcodeFormat, EncodeHintType}
import com.google.zxing.qrcode.QRCodeWriter

private[wechat] object QrText:
  /** Two horizontal characters per module: square modules in a monospace terminal, no ANSI dependency. */
  def render(content: String): String =
    val hints = new java.util.HashMap[EncodeHintType, Object]
    hints.put(EncodeHintType.MARGIN, Int.box(4))
    hints.put(EncodeHintType.CHARACTER_SET, "UTF-8")
    val matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    (0 until matrix.getHeight).map { y =>
      (0 until matrix.getWidth).map(x => if matrix.get(x, y) then "██" else "  ").mkString
    }.mkString("\n")
