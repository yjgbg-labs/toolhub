package groot.wechat

import framework.model.JsonModel

case class WechatLogin(loginId: Option[String] = None, verifyCode: Option[String] = None) derives JsonModel
case class WechatSend(wechatid: String, message: String) derives JsonModel
case class WechatBind(wechatid: String, session: Option[String]) derives JsonModel
case class WechatReceipt(clientId: String, messageId: Option[String]) derives JsonModel
case class WechatAccount(wechatid: String, botId: String, session: Option[String], status: String, pending: Long) derives JsonModel
private[wechat] case class WechatFailure(code: String, message: String, status: Int = 400) extends RuntimeException(message)
private[wechat] case class Account(wechatid: String, botId: String, token: String, baseUrl: String,
    cursor: String = "", context: Option[String] = None, session: Option[String] = None, status: String = "connected")
private[wechat] case class Pending(id: Long, session: String, message: String)
