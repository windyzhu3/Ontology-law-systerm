const mutating = method => ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method.toUpperCase());

// Route.fetch and APIRequestContext sends bypass browser context routing.
export function createRequestGuards(verifyOwnership) {
  return {
    async guardedRouteFetch(route, options) {
      if (mutating(options?.method ?? route.request().method())) verifyOwnership();
      return route.fetch(options);
    },
    async guardedRouteContinue(route, options) {
      if (mutating(options?.method ?? route.request().method())) verifyOwnership();
      return route.continue(options);
    },
    async guardedApiPost(api, url, options) {
      verifyOwnership();
      return api.post(url, options);
    },
  };
}
