const paths = {
  menu: 'M4 6h16M4 12h16M4 18h16', search: 'm21 21-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  arrowLeft: 'm14 5-7 7 7 7', close: 'm6 6 12 12M18 6 6 18', chevronDown: 'm6 9 6 6 6-6', chevronUp: 'm6 15 6-6 6 6',
  swap: 'M7 3v17m-4-4 4 4 4-4M17 21V4m-4 4 4-4 4 4',
  car: 'm5 7 2-4h10l2 4 2 3v7H3v-7l2-3ZM3 10h18M6 17v3M18 17v3M6 13h1m10 0h1',
  motorcycle: 'M8 16a4 4 0 1 1-8 0 4 4 0 0 1 8 0Zm15 0a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM4 16l6-8 5 8M4 16h9l4-10h-4m3-3h3l2 6M8 8h4',
  bike: 'M8 17a4 4 0 1 1-8 0 4 4 0 0 1 8 0Zm16 0a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM4 17l5-9 6 9H4m11 0 4-10-2-4h3M7 5h5m-3 0 1 4',
  walk: 'M14 4a1.5 1.5 0 1 1-3 0 1.5 1.5 0 0 1 3 0ZM7 11l4-4 4 1 3 4M11 7l-1 8-4 6m4-6 5 2 1 4',
  cube: 'm12 2 9 5v10l-9 5-9-5V7l9-5Zm0 10 9-5m-9 5L3 7m9 5v10',
  layers: 'm12 3 10 6-10 6L2 9l10-6ZM2 14l10 6 10-6',
  graph: 'M6 5h12M5 7l6 12M19 7l-6 12M7 4a2 2 0 1 1-4 0 2 2 0 0 1 4 0Zm14 0a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM14 20a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z',
  traffic: 'M8 4a4 4 0 0 1 8 0v16H8V4Zm4 2h.01M12 10h.01M12 14h.01M12 20v2',
  avoid: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM6 6l12 12', navigate: 'm22 2-7 20-4-9-9-4 20-7Z',
  compass: 'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Zm-7-3-2 4-4 2 2-4 4-2Z',
  locate: 'M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0ZM16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM12 1v3m0 16v3M1 12h3m16 0h3',
  plus: 'M12 5v14M5 12h14', minus: 'M5 12h14', straight: 'M12 21V3m-6 6 6-6 6 6',
  right: 'M6 21V11a4 4 0 0 1 4-4h10m-5-5 5 5-5 5', left: 'M18 21V11a4 4 0 0 0-4-4H4m5-5L4 7l5 5',
  pin: 'M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 0 1 16 0ZM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  list: 'M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01', map: 'm9 3-7 3v15l7-3 6 3 7-3V3l-7 3-6-3Zm0 0v15m6-12v15',
  play: 'm7 3 14 9-14 9V3Z', pause: 'M8 4v16M16 4v16', skipBack: 'M5 4v16M19 4 7 12l12 8V4Z', skipForward: 'M19 4v16M5 4l12 8-12 8V4Z',
  refresh: 'M20 7V2m0 5h-5M4 17v5m0-5h5M20 7A9 9 0 0 0 4 5M4 17a9 9 0 0 0 16 2',
  check: 'm5 12 4 4L20 5', info: 'M12 11v6m0-10h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
};
export default function Icon({ name, size = 20, ...props }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}><path d={paths[name] || paths.pin} /></svg>;
}
export function BrandMark({ size = 30, className = '' }) {
  return <svg width={size} height={size + 4} viewBox="0 0 32 36" className={className} aria-hidden="true"><path fill="currentColor" d="M16 1C8 1 2 7 2 15c0 9 14 20 14 20s14-11 14-20C30 7 24 1 16 1Z" /><path d="m9 10 7 8 7-8m-7 8v9" fill="none" stroke="var(--cream-50, #FFFBF0)" strokeWidth="3.6" strokeLinecap="round" strokeLinejoin="round" /></svg>;
}
