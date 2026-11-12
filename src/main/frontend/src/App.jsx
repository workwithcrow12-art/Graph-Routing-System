import { lazy, Suspense, useState } from 'react';
import RoutingWorkspace from './components/RoutingWorkspace.jsx';
import { BrandMark } from './components/Icon.jsx';
const AnalysisWorkspace = lazy(() => import('./components/AnalysisWorkspace.jsx'));
export default function App() {
  const [tab, setTab] = useState('routing');
  const [analysisOpened, setAnalysisOpened] = useState(false);
  const [endpoints, setEndpoints] = useState({ origin: null, destination: null });
  function openAnalysis() { setAnalysisOpened(true); setTab('analysis'); }
  return <>
    <a className="skip-link" href={tab === 'routing' ? '#main-content' : '#analysis-content'}>Đến nội dung chính</a>
    <div hidden={tab !== 'routing'}><RoutingWorkspace onAnalysis={openAnalysis} onEndpointsChange={setEndpoints} active={tab === 'routing'} /></div>
    {analysisOpened && <div hidden={tab !== 'analysis'}><Suspense fallback={<div className="app-loading" role="status"><BrandMark />Đang mở phân tích thuật toán…</div>}>
      <AnalysisWorkspace onBack={() => setTab('routing')} endpoints={endpoints} active={tab === 'analysis'} />
    </Suspense></div>}
  </>;
}
