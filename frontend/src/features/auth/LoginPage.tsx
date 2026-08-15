import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import { Link, useLocation, useNavigate } from 'react-router-dom'
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
        <div className="kicker"><span className="wordmark">FLASH SALE</span></div>
        <h2>登入以搶購</h2>
        <p className="sub">回到搶購佇列繼續完成</p>
        <form noValidate onSubmit={handleSubmit((values) => mutation.mutate(values))}>
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
        <p className="auth-foot">還沒有帳號？<Link to="/register">前往註冊</Link></p>
      </div>
    </div>
  )
}
