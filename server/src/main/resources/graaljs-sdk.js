(function (commandsJson, dispatch) {
  "use strict";
  const commands = JSON.parse(commandsJson);
  const parse = JSON.parse;
  const stringify = JSON.stringify;
  const PromiseClass = Promise;
  const tools = Object.create(null);
  for (const id of commands) {
    const parts = id.split(".");
    let parent = tools;
    for (const name of parts.slice(0, -1)) parent = parent[name] ??= Object.create(null);
    const name = parts[parts.length - 1];
    const call = (input = {}) => {
      return new PromiseClass((resolve, reject) => {
        const json = stringify(input);
        if (json === undefined) throw new TypeError("Command input must be JSON serializable");
        dispatch(id, json, responseJson => {
          const response = parse(responseJson);
          if (response.status >= 200 && response.status < 300) resolve(response);
          else {
            const error = new Error(id + ": HTTP " + response.status + "\n" + stringify(response.body));
            error.name = "ApiError";
            error.operationId = id;
            error.response = response;
            reject(error);
          }
        }, message => reject(new Error(message)));
      });
    };
    Object.setPrototypeOf(call, null);
    delete call.name;
    delete call.length;
    if (parent[name]) Object.assign(call, parent[name]);
    parent[name] = call;
  }

  function freezeTree(tree) {
    for (const value of Object.values(tree)) freezeTree(value);
    Object.freeze(tree);
  }
  freezeTree(tools);
  Object.defineProperty(globalThis, "tools", { value: tools, enumerable: true });
})
