import { useEffect, useId, useState } from 'react';
import { api } from '../api.js';
import Icon from './Icon.jsx';
export default function PlaceSearch({ label, value, onChange, mode, placeholder = 'Nhập tên đường hoặc địa điểm', className = 'place-search', hideLabel = false, disabled = false }) {
  const id = useId();
  const [query, setQuery] = useState(''), [matches, setMatches] = useState([]), [error, setError] = useState('');
  const [editing, setEditing] = useState(false), [active, setActive] = useState(-1), [pending, setPending] = useState(false);
  useEffect(() => {
    setMatches([]); setError(''); setActive(-1); setPending(false);
    if (disabled || !query.trim()) return;
    const controller = new AbortController();
    setPending(true);
    const timer = setTimeout(() => {
      api.searchPlaces(query, { mode, signal: controller.signal }).then((data) => {
        if (!controller.signal.aborted) { setMatches(data.places); setError(data.places.length ? '' : 'Không tìm thấy địa điểm. Thử tên đường gần đó.'); }
      }).catch((err) => { if (!controller.signal.aborted) setError(err.message); })
        .finally(() => { if (!controller.signal.aborted) setPending(false); });
    }, 250);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [query, mode, disabled]);
  function select(place) { if (disabled) return; onChange(place); setQuery(''); setMatches([]); setEditing(false); setActive(-1); }
  return <div className={className} onBlur={(event) => {
    if (!event.currentTarget.contains(event.relatedTarget)) { setEditing(false); setQuery(''); }
  }}>
    <label className={hideLabel ? 'sr-only' : ''} htmlFor={id}>{label}</label>
    <div className="search-input-wrap">
      <input id={id} type="search" role="combobox" disabled={disabled} aria-autocomplete="list" aria-expanded={!disabled && !!matches.length}
        aria-controls={`${id}-results`} aria-activedescendant={active >= 0 ? `${id}-${active}` : undefined}
        value={editing ? query : value?.displayName || value?.name || query} placeholder={placeholder}
        autoComplete="off" onFocus={() => setEditing(true)}
        onChange={(event) => { setQuery(event.target.value); setEditing(true); setError(''); }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' && matches.length) { event.preventDefault(); setActive((index) => (index + 1) % matches.length); }
          if (event.key === 'ArrowUp' && matches.length) { event.preventDefault(); setActive((index) => index < 0 ? matches.length - 1 : (index - 1 + matches.length) % matches.length); }
          if (event.key === 'Enter' && matches.length) { event.preventDefault(); select(matches[Math.max(0, active)]); }
          if (event.key === 'Escape') { setQuery(''); setMatches([]); setEditing(false); }
        }} />
      {value && <button className="clear-place icon-button" type="button" disabled={disabled} aria-label={`Xóa ${label}`} onClick={() => select(null)}><Icon name="close" size={15} /></button>}
    </div>
    {!disabled && !!matches.length && <ul id={`${id}-results`} className="search-results" role="listbox" aria-label={`Gợi ý ${label}`}>{matches.map((place, index) => <li key={place.id} role="option" id={`${id}-${index}`} aria-selected={active === index}>
      <button type="button" tabIndex={-1} onMouseDown={(event) => event.preventDefault()} onClick={() => select(place)}><Icon name="pin" size={18} /><span>{place.displayName || place.name}<small>Thanh Xuân, Hà Nội{place.mainComponentModes && !place.mainComponentModes.includes(mode) ? ' · ngoài mạng đường chính' : ''}</small></span></button>
    </li>)}</ul>}
    {(pending || error) && editing && <p className="search-status" role="status">{pending ? 'Đang tìm địa điểm…' : error}</p>}
  </div>;
}
