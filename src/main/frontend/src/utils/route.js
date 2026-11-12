export function formatDuration(seconds) {
  if (!Number.isFinite(seconds)) return '—';
  const total = Math.max(0, Math.round(seconds));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const remaining = total % 60;
  const parts = [];
  if (hours) parts.push(`${hours} giờ`);
  if (minutes) parts.push(`${minutes} phút`);
  if (remaining || !parts.length) parts.push(`${remaining} giây`);
  return parts.join(' ');
}

export function formatDistance(meters) {
  if (!Number.isFinite(meters)) return '—';
  if (meters < 1000) return `${Math.max(0, Math.round(meters))} m`;
  return `${(meters / 1000).toLocaleString('vi-VN', {
    minimumFractionDigits: 2, maximumFractionDigits: 2,
  })} km`;
}

export function visibleRoutes(routes = []) {
  return routes.filter((route, index) => index === 0 || route.sameNodeSequenceAsPrimary !== true);
}

export function mergeDirections(directions = []) {
  const merged = [];
  for (const direction of directions) {
    const previous = merged.at(-1);
    if (direction.maneuver === 'CONTINUE' && previous?.maneuver === 'CONTINUE'
      && direction.roadName?.trim() && direction.roadName === previous.roadName) {
      previous.to = direction.to;
      previous.distanceMeters += direction.distanceMeters;
      previous.durationSeconds = Number.isFinite(previous.durationSeconds) && Number.isFinite(direction.durationSeconds)
        ? previous.durationSeconds + direction.durationSeconds : undefined;
    } else {
      merged.push({ ...direction });
    }
  }
  return merged;
}
