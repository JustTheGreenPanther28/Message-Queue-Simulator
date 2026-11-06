// Simple line icons (24x24, stroke = currentColor).
const Svg = ({ children, ...p }) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="square" aria-hidden="true" {...p}>
    {children}
  </svg>
)

export const ChatIcon = () => <Svg><path d="M4 5h16v11H9l-5 4z" /></Svg>
export const PlusPersonIcon = () => <Svg><circle cx="9" cy="8" r="3.5" /><path d="M3 20c0-3.5 2.7-6 6-6s6 2.5 6 6" /><path d="M19 8v6M16 11h6" /></Svg>
export const GroupIcon = () => <Svg><circle cx="8" cy="9" r="3" /><circle cx="16.5" cy="9" r="2.5" /><path d="M2.5 19c0-3 2.5-5.2 5.5-5.2s5.5 2.2 5.5 5.2M14 14.2c3.3-.6 7 1.3 7 4.8" /></Svg>
export const BellIcon = () => <Svg><path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z" /><path d="M10 20h4" /></Svg>
export const SunIcon = () => <Svg><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></Svg>
export const MoonIcon = () => <Svg><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" /></Svg>
export const BackIcon = () => <Svg><path d="M20 12H5M11 6l-6 6 6 6" /></Svg>
export const CloseIcon = () => <Svg><path d="M6 6l12 12M18 6L6 18" /></Svg>
export const SendIcon = () => <Svg><path d="M3 11l18-8-8 18-2-8z" /></Svg>
export const ImageIcon = () => <Svg><rect x="3" y="4" width="18" height="16" /><circle cx="9" cy="10" r="2" /><path d="M21 17l-5-5-9 8" /></Svg>
export const InfoIcon = () => <Svg><rect x="3" y="3" width="18" height="18" /><path d="M12 11v6M12 7v1" /></Svg>
export const CheckIcon = () => <Svg strokeWidth="3"><path d="M5 12l5 5 9-10" /></Svg>
export const SearchIcon = () => <Svg><circle cx="11" cy="11" r="6" /><path d="M20 20l-4.5-4.5" /></Svg>
export const MoreIcon = () => <Svg><path d="M5 12h.01M12 12h.01M19 12h.01" strokeWidth="3" /></Svg>
export const SignOutIcon = () => <Svg><path d="M14 4h6v16h-6M10 8l-4 4 4 4M6 12h10" /></Svg>

/** Message status: clock (pending), one tick (sent), two ticks (delivered / read), "!" (failed). */
export function Ticks({ status }) {
  if (status === 'PENDING') {
    return <span className="ticks" title="Sending"><svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6"><circle cx="8" cy="8" r="6" /><path d="M8 4.5V8l2.5 1.5" /></svg></span>
  }
  if (status === 'FAILED') {
    return <span className="ticks failed" title="Not sent"><svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="8" cy="8" r="6.5" /><path d="M8 4.5v4.5M8 11v.5" /></svg></span>
  }
  const double = status === 'DELIVERED' || status === 'READ'
  const label = status === 'READ' ? 'Read' : status === 'DELIVERED' ? 'Delivered' : 'Sent'
  return (
    <span className={`ticks ${status === 'READ' ? 'read' : ''}`} title={label} aria-label={label}>
      <svg viewBox={double ? '0 0 22 14' : '0 0 16 14'} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="square">
        <path d="M1.5 7.5l4 4 8-9" />
        {double && <path d="M9.5 11.5l8-9" />}
      </svg>
    </span>
  )
}
