import { api } from '../api.js';

let mapRequest;

export function loadMap() {
  if (!mapRequest) {
    mapRequest = api.map().catch((error) => {
      mapRequest = undefined;
      throw error;
    });
  }
  return mapRequest;
}
