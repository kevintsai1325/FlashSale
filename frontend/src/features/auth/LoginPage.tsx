import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from './useAuth'
import './AuthPages.css'

const schema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
})

type FormValues = z.infer<typeof schema>

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { login } = useAuth()
  const from = (location.state as { from?: string } | null)?.from ?? '/'
  const prefillEmail = (location.state as { email?: string } | null)?.email ?? ''
  const { register, handleSubmit, formState: { errors } } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: prefillEmail },
  })
  const mutation = useMutation({
    mutationFn: (values: FormValues) => login(values.email, values.password),
    onSuccess: () => navigate(from),
  })

  return (
    <div className="auth-body">
      <div className="auth-card">
        <h2>Login</h2>
        <form onSubmit={handleSubmit((values) => mutation.mutate(values))}>
          <div className="field">
            <label htmlFor="email">Email</label>
            <input id="email" type="email" {...register('email')} />
            {errors.email && <span role="alert">{errors.email.message}</span>}
          </div>

          <div className="field">
            <label htmlFor="password">Password</label>
            <input id="password" type="password" {...register('password')} />
            {errors.password && <span role="alert">{errors.password.message}</span>}
          </div>

          <button type="submit" className="btn btn-primary btn-block">Login</button>
          {mutation.isError && <p role="alert">{(mutation.error as Error).message}</p>}
        </form>
      </div>
    </div>
  )
}
