interface SessionRouter {
  state: {
    location: { pathname: string; search: string; hash: string }
  }
  navigate: (to: string, options: {
    replace: boolean
    state: { from: string }
  }) => Promise<unknown>
}

interface ClearableQueryClient {
  clear: () => void
}

export async function redirectExpiredSession(
  router: SessionRouter,
  queryClient: ClearableQueryClient,
) {
  const { pathname, search, hash } = router.state.location
  queryClient.clear()
  await router.navigate('/login', {
    replace: true,
    state: { from: pathname + search + hash },
  })
}
