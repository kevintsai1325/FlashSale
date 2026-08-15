import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import * as authApi from '../../api/authApi'
import './AuthPages.css'

const schema = z.object({
  email: z.string().email(),
  password: z.string().min(8),
})

type FormValues = z.infer<typeof schema>

export function RegisterPage() {
  const navigate = useNavigate()
  const { register, handleSubmit, formState: { errors } } = useForm<FormValues>({ resolver: zodResolver(schema) })
  const mutation = useMutation({
    mutationFn: (values: FormValues) => authApi.register(values.email, values.password),
    onSuccess: (_, values) => navigate('/login', { state: { email: values.email } }),
  })

  return (
    <div className="auth-body">
      <div className="auth-card">
        <div className="kicker"><span className="wordmark">FLASH SALE</span></div>
        <h2>建立帳號</h2>
        <p className="sub">搶購前先取得你的入場資格</p>
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

          <button type="submit" className="btn btn-primary btn-block">Register</button>
          {mutation.isError && <p role="alert">{(mutation.error as Error).message}</p>}
        </form>
        <p className="auth-foot">已經有帳號？<Link to="/login">前往登入</Link></p>
      </div>
    </div>
  )
}
