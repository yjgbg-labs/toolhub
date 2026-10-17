package sprout

trait SuccessResponse[A]:
  private[sprout] def declare(scope: EndpointScope): Output[A]

object SuccessResponse extends JsonSuccessResponses:
  given SuccessResponse[String] with
    private[sprout] def declare(scope: EndpointScope): Output[String] =
      scope.output[String](200, Some("text/plain"), Some(JsonModel[String].schema), value => Some(value))

  given SuccessResponse[Unit] with
    private[sprout] def declare(scope: EndpointScope): Output[Unit] =
      scope.output[Unit](200, None, None, _ => None)

private[sprout] trait JsonSuccessResponses:
  given [A](using model: JsonModel[A]): SuccessResponse[A] with
    private[sprout] def declare(scope: EndpointScope): Output[A] =
      scope.output[A](200, Some("application/json"), Some(model.schema), value => Some(model.encoder(value).noSpaces))
