import { useEffect } from 'react'
import Avatar from './Avatar.jsx'
import { CloseIcon } from './Icons.jsx'

export function Toasts({ toasts, onOpen }) {
  return (
    <div className="toasts" aria-live="polite">
      {toasts.map((t) => (
        <button key={t.id} className={`toast ${t.kind === 'error' ? 'error' : ''}`} onClick={() => onOpen(t)}>
          {t.kind !== 'error' && <Avatar name={t.title} url={t.imageUrl} colorId={t.colorId} size={44} />}
          <div className="grow">
            <div className="t">{t.title}</div>
            <div className="b">{t.body}</div>
          </div>
        </button>
      ))}
    </div>
  )
}

export function Lightbox({ src, onClose }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="lightbox" onClick={onClose} role="dialog" aria-label="Image">
      <button className="icon-btn" aria-label="Close"><CloseIcon /></button>
      <img src={src} alt="" />
    </div>
  )
}
