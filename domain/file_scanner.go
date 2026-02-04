package domain

type ScanRequest struct {
	Path string `validate:"required,max=1024"`
}

type ScanResponse struct {
	Started bool
}
