package observability

import (
	"context"
	"log/slog"
	"runtime"
	"sync"
	"sync/atomic"
	"time"
)

// RecentBatchInfo représente un fichier dans le pipeline gRPC
type RecentBatchInfo struct {
	ID        string `json:"id"`
	Path      string `json:"path"`
	Mime      string `json:"mime"`
	Status    string `json:"status"`
	Timestamp string `json:"timestamp"`
}

// MonitoringStats agrège toutes les métriques pour l'UI
type MonitoringStats struct {
	// --- SCANNER METRICS ---
	ScannerDiskSpeed float64 `json:"scanner_disk_speed"` // Mo/s (Lecture brute)
	FilesFound       uint64  `json:"files_found"`

	// --- MASTER METRICS ---
	MasterNetSpeed  float64 `json:"master_net_speed"` // Mo/s (Envoi gRPC)
	ProcessingCount uint32  `json:"processing_count"`
	MaxCapacity     uint32  `json:"max_capacity"`

	// --- SYSTEM METRICS ---
	AllocMemMb       uint64            `json:"alloc_mem_mb"`
	NumGC            uint32            `json:"num_gc"`
	CurrentQueueSize int               `json:"current_queue_size"`
	RecentBatches    []RecentBatchInfo `json:"recent_batches"`
}

// MonitoringManager gère la télémétrie en temps réel
type MonitoringManager struct {
	log         *slog.Logger
	mu          sync.RWMutex
	latestStats MonitoringStats

	// Compteurs atomiques pour les débits (en bytes)
	ScannerBytes uint64
	MasterBytes  uint64
	FilesFound   uint64
	SkippedItem  uint64
	DirsScanned  uint64
	ErrorCount   uint64
	LastCheck    time.Time
}

func NewMonitoringManager(log *slog.Logger) *MonitoringManager {
	return &MonitoringManager{
		log:       log,
		LastCheck: time.Now(),
		latestStats: MonitoringStats{
			RecentBatches: make([]RecentBatchInfo, 0),
		},
	}
}

func (mm *MonitoringManager) IncrErrorCount() {
	atomic.AddUint64(&mm.ErrorCount, 1)
}

func (mm *MonitoringManager) IncrDirsScanned() {
	atomic.AddUint64(&mm.DirsScanned, 1)
}

func (mm *MonitoringManager) IncrSkippedItems() {
	atomic.AddUint64(&mm.SkippedItem, 1)
}

func (mm *MonitoringManager) IncrFileFound() {
	atomic.AddUint64(&mm.FilesFound, 1)
}

// IncrScannerBytes ajoute des bytes lus par le scanner
func (mm *MonitoringManager) IncrScannerBytes(n uint64) {
	atomic.AddUint64(&mm.ScannerBytes, n)
}

// IncrMasterBytes ajoute des bytes envoyés par le master aux spécialistes
func (mm *MonitoringManager) IncrMasterBytes(n uint64) {
	atomic.AddUint64(&mm.MasterBytes, n)
}

// AddBatch ajoute un batch récent à la liste (thread-safe)
func (mm *MonitoringManager) AddBatch(id, path, mime, status string) {
	mm.mu.Lock()
	defer mm.mu.Unlock()

	batch := RecentBatchInfo{
		ID:        id,
		Path:      path,
		Mime:      mime,
		Status:    status,
		Timestamp: time.Now().Format("15:04:05"),
	}

	// Ajouter au début de la liste
	mm.latestStats.RecentBatches = append([]RecentBatchInfo{batch}, mm.latestStats.RecentBatches...)

	// Garder seulement les 20 derniers
	if len(mm.latestStats.RecentBatches) > 20 {
		mm.latestStats.RecentBatches = mm.latestStats.RecentBatches[:20]
	}
}

// 🔴 VERSION CORRIGÉE - Listen avec calcul automatique des métriques
func (mm *MonitoringManager) Listen(ctx context.Context, metricsChan <-chan MonitoringStats) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			mm.log.Info("🛑 Monitoring manager arrêté")
			return

		case <-ticker.C:
			// 🔴 CALCUL AUTOMATIQUE DES STATS (pas besoin de recevoir du channel)
			mm.updateStats()

		case stats, ok := <-metricsChan:
			if !ok {
				mm.log.Info("📭 Channel de monitoring fermé")
				return
			}
			// Si on reçoit des stats externes, on les fusionne
			mm.MergeExternalStats(stats)
		}
	}
}

// 🆕 NOUVELLE FONCTION - Calcule les stats automatiquement
func (mm *MonitoringManager) updateStats() {
	mm.mu.Lock()
	defer mm.mu.Unlock()

	now := time.Now()
	duration := now.Sub(mm.LastCheck).Seconds()

	if duration > 0 {
		// Lire et réinitialiser les compteurs de bytes
		sBytes := atomic.SwapUint64(&mm.ScannerBytes, 0)
		mBytes := atomic.SwapUint64(&mm.MasterBytes, 0)

		// Calculer les vitesses en MB/s
		mm.latestStats.ScannerDiskSpeed = (float64(sBytes) / 1024 / 1024) / duration
		mm.latestStats.MasterNetSpeed = (float64(mBytes) / 1024 / 1024) / duration
	}
	mm.LastCheck = now

	// Charger les compteurs cumulés
	mm.latestStats.FilesFound = atomic.LoadUint64(&mm.FilesFound)

	// Métriques système Go
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	mm.latestStats.AllocMemMb = m.Alloc / 1024 / 1024
	mm.latestStats.NumGC = m.NumGC

	// Log pour debug
	mm.log.Debug("📊 Stats mises à jour",
		"scanner_speed", mm.latestStats.ScannerDiskSpeed,
		"master_speed", mm.latestStats.MasterNetSpeed,
		"files_found", mm.latestStats.FilesFound,
		"mem_mb", mm.latestStats.AllocMemMb,
	)
}

// 🆕 NOUVELLE FONCTION - Fusionne des stats reçues d'ailleurs
func (mm *MonitoringManager) MergeExternalStats(stats MonitoringStats) {
	mm.mu.Lock()
	defer mm.mu.Unlock()

	// On garde les débits calculés localement
	// mais on prend les autres infos de l'externe
	mm.latestStats.CurrentQueueSize = stats.CurrentQueueSize
	mm.latestStats.MaxCapacity = stats.MaxCapacity
	mm.latestStats.ProcessingCount = stats.ProcessingCount

	// On peut aussi fusionner les batches si besoin
	if len(stats.RecentBatches) > 0 {
		mm.latestStats.RecentBatches = stats.RecentBatches
	}

	mm.log.Info("📊 Stats externes fusionnées",
		"queue_size", stats.CurrentQueueSize,
		"max_capacity", stats.MaxCapacity,
	)
}

func (mm *MonitoringManager) GetLatest() MonitoringStats {
	mm.mu.RLock()
	defer mm.mu.RUnlock()

	// Log pour debug
	mm.log.Debug("🔍 GetLatest() appelé",
		"scanner_speed", mm.latestStats.ScannerDiskSpeed,
		"master_speed", mm.latestStats.MasterNetSpeed,
		"files_found", mm.latestStats.FilesFound,
	)

	return mm.latestStats
}

func (mm *MonitoringManager) UpdateQueue(size int, max uint32) {
	mm.mu.Lock()
	defer mm.mu.Unlock()
	mm.latestStats.CurrentQueueSize = size
	mm.latestStats.MaxCapacity = max
}
