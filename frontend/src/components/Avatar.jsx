import { initials, tileColor } from '../lib/format.js'
import { GroupIcon } from './Icons.jsx'

/** Square tile: the picture if there is one, else initials on a colour picked from the id. */
export default function Avatar({ name, url, colorId, size = 40, group = false }) {
  const style = { width: size, height: size, background: tileColor(colorId), fontSize: Math.round(size * 0.38) }
  return (
    <span className="avatar" style={style} aria-hidden="true">
      {url ? <img src={url} alt="" loading="lazy" /> : group ? <GroupIcon /> : initials(name)}
    </span>
  )
}
